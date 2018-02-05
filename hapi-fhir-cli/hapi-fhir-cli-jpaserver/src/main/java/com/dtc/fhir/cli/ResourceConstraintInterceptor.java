package com.dtc.fhir.cli;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.hibernate.cache.ehcache.management.impl.BeanUtils;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ca.uhn.fhir.model.api.Bundle;
import ca.uhn.fhir.rest.server.interceptor.RequestValidatingInterceptor;
import ca.uhn.fhir.validation.IValidationContext;
import ca.uhn.fhir.validation.IValidatorModule;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.SingleValidationMessage;

/**
 * 對新增、修改的資料進行欄位驗證
 */
public class ResourceConstraintInterceptor extends RequestValidatingInterceptor implements IValidatorModule {
	private static final String PROP_FILE_NAME = "constraint.properties";
	private Logger logger = LoggerFactory.getLogger(this.getClass());
	private Map<String,Constraint> constraintMap = new HashMap();

	public ResourceConstraintInterceptor() {
		InputStream is = getClass().getClassLoader().getResourceAsStream(PROP_FILE_NAME);
		if (is == null) {
			logger.info(PROP_FILE_NAME + " not found");
			return;
		}

		try {
			Properties prop = new Properties();
			prop.load(is);

			for (Map.Entry<Object, Object> e : prop.entrySet()) {
				String key = (String)e.getKey();
				addConstraint(key);
				logger.info(key);
			}
			addValidatorModule(this);

			setFailOnSeverity(ResultSeverityEnum.ERROR);
			setAddResponseHeaderOnSeverity(ResultSeverityEnum.INFORMATION);
			setResponseHeaderValue("Validation on ${line}: ${message} ${severity}");
			setResponseHeaderValueNoIssues("No issues detected");
			logger.info("ResourceConstraintInterceptor initialize completed.");
		} catch (IOException e) {
			logger.error("Load " + PROP_FILE_NAME + " failure.", e);
		}
	}

	/**
	 * 依照設定值產生限制條件，目前設定值只需要定義 Property 的 key
	 */
	private void addConstraint(String prop) {
		try {
			String[] value = prop.split("\\.");

			if (value.length <= 1) { return; } //無用的設定值

			String resourceName = value[0];

			Constraint cons = constraintMap.get(resourceName);

			if (cons == null) {
				cons = new Constraint(resourceName);
				constraintMap.put(resourceName, cons);
			}

			cons.add(value[1]);
		} catch (Exception e) {
			//Ignore
		}
	}

	@Override
	public void validateResource(IValidationContext<IBaseResource> theCtx) {
		//取得對應限制的 Constraint
		String resourceName = theCtx.getResource().getClass().getSimpleName();
		Constraint cons = constraintMap.get(resourceName);

		if (cons == null) { return; } //no defined constraint

		Object resource = theCtx.getResource();
		for (String field : cons.fields) {
			Object value = BeanUtils.getBeanProperty(resource, field);

			//限制條件判斷，目前只有限制指定的欄位不可以為 null
			if (value == null) {
				theCtx.addValidationMessage(
					errorValidationMessage(
						resourceName + " constraint conflict: " + field + " cannot be null."
					)
				);
				return;
			}

			//for collection field
			if (isEmptyCollection(value)) {
				theCtx.addValidationMessage(
					errorValidationMessage(
						resourceName + " constraint conflict: " + field + " cannot be empty."
					)
				);
				return;
			}
		}
	}

	private boolean isEmptyCollection(Object obj) {
		if (obj instanceof Collection) {
			if (((Collection<?>)obj).isEmpty()) {
				return true;
			}
		}

		return false;
	}

	private SingleValidationMessage errorValidationMessage(String msg) {
		SingleValidationMessage validationMsg = new SingleValidationMessage();
		validationMsg.setMessage(msg);
		validationMsg.setSeverity(ResultSeverityEnum.ERROR);
		return validationMsg;
	}

	@Override
	public void validateBundle(IValidationContext<Bundle> theContext) {
		// do nothing
	}
}

/**
 * 代表一個 Resource 中所有 field 的限制
 */
class Constraint {
	final String resourceName;
	final List<String> fields = new ArrayList();

	Constraint(String resourceName) {
		this.resourceName = resourceName;
	}

	void add(String field) {
		fields.add(field);
	}
}