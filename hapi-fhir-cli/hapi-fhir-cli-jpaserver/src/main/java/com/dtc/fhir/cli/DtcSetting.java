package com.dtc.fhir.cli;

import java.io.File;
import com.dtc.common.DoubleProperties;

public class DtcSetting extends DoubleProperties {
	private static DtcSetting instance = new DtcSetting();

	static {
		//為了方便工程部檢查錯誤，啟動時就強制檢查
		instance.checkOutterProperties();
	}

	private DtcSetting() {
		super("dtc-config.xml", new File("dtc-fhir.properties"));
	}

	public static String dxorderCreateApiPath() {
		return instance.getProperty("dxorder.create.api.path");
	}

	/**
	 * 確認外部設定檔是否存在
	 */
	public void checkOutterProperties() {
		System.out.println("[OutterProperties] exists: " + new File("dtc-fhir.properties").exists());
	}
}
