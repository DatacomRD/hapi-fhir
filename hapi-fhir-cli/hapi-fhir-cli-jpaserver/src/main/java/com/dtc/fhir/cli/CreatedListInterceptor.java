package com.dtc.fhir.cli;

import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicNameValuePair;
import org.hl7.fhir.instance.model.api.IBaseResource;
import com.google.common.io.CharStreams;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import ca.uhn.fhir.model.api.TemporalPrecisionEnum;
import ca.uhn.fhir.model.dstu2.composite.CodeableConceptDt;
import ca.uhn.fhir.model.dstu2.composite.CodingDt;
import ca.uhn.fhir.model.dstu2.composite.IdentifierDt;
import ca.uhn.fhir.model.dstu2.composite.ResourceReferenceDt;
import ca.uhn.fhir.model.dstu2.resource.AuditEvent;
import ca.uhn.fhir.model.dstu2.resource.AuditEvent.Event;
import ca.uhn.fhir.model.dstu2.resource.AuditEvent.ObjectElement;
import ca.uhn.fhir.model.dstu2.resource.Bundle;
import ca.uhn.fhir.model.dstu2.resource.DiagnosticOrder;
import ca.uhn.fhir.model.dstu2.resource.Encounter;
import ca.uhn.fhir.model.dstu2.resource.ListResource;
import ca.uhn.fhir.model.dstu2.resource.Organization;
import ca.uhn.fhir.model.dstu2.resource.Patient;
import ca.uhn.fhir.model.dstu2.resource.Practitioner;
import ca.uhn.fhir.model.dstu2.valueset.AuditEventActionEnum;
import ca.uhn.fhir.model.dstu2.valueset.DiagnosticOrderPriorityEnum;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.client.IGenericClient;
import ca.uhn.fhir.rest.method.RequestDetails;
import ca.uhn.fhir.rest.server.interceptor.ServerOperationInterceptorAdapter;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;

/**
 * 對二級平台產生流程（{@link DiagnosticOrder}）資料
 */
public class CreatedListInterceptor extends ServerOperationInterceptorAdapter {
	private static final String FAIL_RESOURCE_KEY = UUID.randomUUID().toString();
	private static final String FAIL_MSG_KEY = UUID.randomUUID().toString();
	private static final String DXORDER_CREATE_API_PATH = DtcSetting.dxorderCreateApiPath();
	private static final String DXORDER_ID_PREFIX = "DXOD.";
	private static final String ENCOUNTER_CODING_SYSTEM = "http://www.datacom.com.tw/TWRCore/Encounter";
	private static final String ORDER_STATION_URL_SYSTEM = "OrderStationUrl";

	private IGenericClient client; //Thread-safe
	private String lv2OrderStationApiUrl;
	private Gson gson = new GsonBuilder().create();

	/**
	 * 初始化 lv2OrderStationApiUrl
	 */
	private void init(RequestDetails detail) throws Exception {
		System.out.println("DXORDER_CREATE_API_PATH " + DXORDER_CREATE_API_PATH);
		if (lv2OrderStationApiUrl != null) { return; }

		client = detail.getServer().getFhirContext().newRestfulGenericClient(detail.getFhirServerBase());

		//lv2OrderStationApiUrl
		Bundle bundle = client.search()
			.forResource(Organization.class)
			.where(Organization.IDENTIFIER.exactly().systemAndCode("MedLevel", "03"))
			.returnBundle(Bundle.class)
			.execute();

		Organization lv2Org = (Organization)bundle.getEntryFirstRep().getResource();

		if (lv2Org == null) { throw new Exception("無法取得二級平台的 Organization resource"); }

		for (IdentifierDt identifier : lv2Org.getIdentifier()) {
			if (ORDER_STATION_URL_SYSTEM.equals(identifier.getSystem())) {
				lv2OrderStationApiUrl = identifier.getValue() + DXORDER_CREATE_API_PATH;
				break;
			}
		}

		if (lv2OrderStationApiUrl == null || lv2OrderStationApiUrl.isEmpty()) {
			lv2OrderStationApiUrl = null;
			throw new Exception("二級平台未設定 OrderStationUrl");
		}
	}

	/**
	 * 只有 Resource 建立「完成」才會 call 此 method
	 * <p>
	 * 注意：若發生任何錯誤會導致該 Resource 的建立被 rollback，所以裡面使用了 try-catch 保護
	 */
	@Override
	public void resourceCreated(RequestDetails detail, IBaseResource baseResource) {

		if (!(baseResource instanceof ListResource)) { return; } //只有 ListResource 才動作

		ListResource resource = (ListResource)baseResource;

		if (!isOpd(resource) && isFromEmr(resource)) { return; } //只有 EMR 的門診病歷才處理

		try {
			init(detail); //初始化

			//以每個發 request 的 client 的相對 url 為準
			String fhirBaseUrl = detail.getFhirServerBase() + "/";

			//產生 DxOrder
			DiagnosticOrder dxOrder = createDxOrder(resource, fhirBaseUrl);

			//取得 modality
			Encounter encounter = client.read(Encounter.class, resource.getEncounter().getReference());
			String modality = modalityFrom(encounter);

			//往二級平台 call /DxOrder/create API
			String xmlData = detail
				.getServer()
				.getFhirContext()
				.newXmlParser()
				.encodeResourceToString(dxOrder);

			ArrayList<NameValuePair> paramList = new ArrayList<NameValuePair>();
			paramList.add(new BasicNameValuePair("xml", xmlData));
			paramList.add(new BasicNameValuePair("modality", modality));

			String response = postFormData(lv2OrderStationApiUrl, paramList);
			/*
			 * Reference: http://192.168.1.47:8000/docCenter/OrderStation-TWR/ApiSpec.md
			 * status：
			 * 	- 1：建立成功
			 *	- 0：建立失敗
			 *	- -1：建立失敗，xml 參數空白
			 *	- -2：建立失敗，modality 參數不在提供的 list 中
			 * 	- -3：建立失敗，其他錯誤
			 * message：錯誤訊息
			 */
			@SuppressWarnings("serial")
			Type type = new TypeToken<Map<String, String>>(){}.getType();
			Map<String, String> map = gson.fromJson(response, type);
			String status = map.get("status");

			if ("1".equals(status)) { return; }

			//失敗
			throw new Exception("[" + status + "] " + map.get("message"));
		} catch (Exception e) {
			e.printStackTrace();
			Map<Object,Object> map = detail.getUserData();
			map.put(FAIL_RESOURCE_KEY, resource);
			map.put(FAIL_MSG_KEY, e.getMessage());
		}
	}

	@Override
	public void processingCompletedNormally(ServletRequestDetails detail) {
		//因為在 resourceCreated() 中若進行此失敗流程會無法 reference 到新增的 resource 而導致失敗
		//所以必須在這個時間點處理錯誤流程
		ListResource resource = (ListResource)detail.getUserData().get(FAIL_RESOURCE_KEY);

		if (resource == null) { return; }

		AuditEvent auditEvent = new AuditEvent();

		//時間、錯誤訊息
		Event event = auditEvent.getEvent();
		event.setDateTime(new Date(), TemporalPrecisionEnum.MILLI);
		event.setOutcomeDesc((String)detail.getUserData().get(FAIL_MSG_KEY));
		event.setAction(AuditEventActionEnum.CREATE);

		//Reference
		ObjectElement object = auditEvent.getObjectFirstRep();
		object.setReference(new ResourceReferenceDt(resource));
		object.getIdentifier().setSystem("Creator");
		object.getIdentifier().setValue(getClass().getSimpleName());

		//sourceAddress
		auditEvent.getSource().setSite(detail.getServletRequest().getRemoteAddr());

		//targetNetwork
		auditEvent.getParticipantFirstRep().getNetwork().setAddress(lv2OrderStationApiUrl);

		MethodOutcome outcome = client.create().resource(auditEvent).execute(); //XXX 記錄在 log 中
		msg("[outcome] " + outcome.toString());
	}

	/**
	 * 判斷是否為門診病歷
	 */
	private boolean isOpd(ListResource resource) {
		for (CodingDt coding : resource.getCode().getCoding()) {
			if ("OPD".equals(coding.getCode())) {
				return true;
			}
		}

		return false;
	}

	/**
	 * 判斷是否來源為 EMR
	 */
	private boolean isFromEmr(ListResource resource) {
		for (IdentifierDt identifier : resource.getIdentifier()) {
			if ("Creator".equals(identifier.getSystem()) &&
				"EMR".equals(identifier.getValue())) {
				return true;
			}
		}

		return false;
	}

	/**
	 * 建立要產生在二級平台的 {@link DiagnosticOrder} 內容
	 */
	private DiagnosticOrder createDxOrder(ListResource src, String fhirBaseUrl) {
		DiagnosticOrder dxOrder = new DiagnosticOrder();

		//id
		dxOrder.setId(
			composeDxOrderId(
				src.getSubject().getReference().getIdPart().toString(),
				src.getEncounter().getReference().getIdPart().toString()
			)
		);

		//subject
		dxOrder.setSubject(loadSubject(src.getSubject(), fhirBaseUrl));

		//orderer
		dxOrder.setOrderer(loadOrderer(src.getSource(), fhirBaseUrl));

		//encounter
		ResourceReferenceDt ecntRef = src.getEncounter();
		ecntRef.setReference(fhirBaseUrl + ecntRef.getReference());
		dxOrder.setEncounter(ecntRef);

		//priority
		dxOrder.setPriority(DiagnosticOrderPriorityEnum.ROUTINE);

		return dxOrder;
	}

	/**
	 * 格式：DXOD.機構碼.病歷號.ECNT流水號
	 */
	private String composeDxOrderId(String patientId, String encountId) {
		return DXORDER_ID_PREFIX +
			patientId.substring(5) +
			".ECNT" +
			encountId.substring(encountId.lastIndexOf(".") + 1);
	}

	private ResourceReferenceDt loadSubject(ResourceReferenceDt subject, String fhirBaseUrl) {
		Patient patient = client.read(Patient.class, subject.getReference());
		ResourceReferenceDt referenceDt = new ResourceReferenceDt();
		referenceDt.setReference(fhirBaseUrl + subject.getReference());
		referenceDt.setDisplay(patient.getName().get(0).getText());
		return referenceDt;
	}

	private ResourceReferenceDt loadOrderer(ResourceReferenceDt orderer, String fhirBaseUrl) {
		Practitioner practitioner = client.read(Practitioner.class, orderer.getReference());
		ResourceReferenceDt referenceDt = new ResourceReferenceDt();
		referenceDt.setReference(fhirBaseUrl + orderer.getReference());
		referenceDt.setDisplay(practitioner.getName().getText());
		return referenceDt;
	}

	private String modalityFrom(Encounter encounter) {
		for(CodeableConceptDt concept : encounter.getType()) {
			for(CodingDt coding : concept.getCoding()) {
				if (ENCOUNTER_CODING_SYSTEM.equals(coding.getSystem())) {
					return coding.getCode();
				}
			}
		}
		return null;
	}

	/**
	 * 將資料以 x-www-form-urlencoded 的 MIME-TYPE 格式送出
	 */
	private String postFormData(String url, ArrayList<NameValuePair> paramList) throws IOException {
		HttpPost postRequest = new HttpPost(url);
		postRequest.setEntity(new UrlEncodedFormEntity(paramList, StandardCharsets.UTF_8));

		HttpClient client = HttpClientBuilder.create().build();

		HttpResponse response = null;
		try {
			return getContentString(client.execute(postRequest));
		} finally {
			closeResponse(response);
		}
	}

	private String getContentString(HttpResponse response) throws IOException {
		return CharStreams.toString(
			new InputStreamReader(
				response.getEntity().getContent(),
				StandardCharsets.UTF_8
			)
		);
	}

	private void closeResponse(HttpResponse response) {
		if (response != null && response instanceof CloseableHttpResponse) {
			try {
				((CloseableHttpResponse) response).close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	private void msg(String text) {
		System.out.println("[Interceptor] " + text);
	}
}
