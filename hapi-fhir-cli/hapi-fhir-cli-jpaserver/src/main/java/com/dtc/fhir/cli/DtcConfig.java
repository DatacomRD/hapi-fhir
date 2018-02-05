package com.dtc.fhir.cli;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DtcConfig {
	private static final String PROP_FILE_NAME = "config.properties";
	private static Logger logger = LoggerFactory.getLogger(DtcConfig.class.getClass());
	private static Properties prop = new Properties();

	static {
		InputStream is = DtcConfig.class.getClassLoader().getResourceAsStream(PROP_FILE_NAME);
		if (is == null) {
			logger.info(PROP_FILE_NAME + " not found");
		} else {
			try {
				prop.load(is);
				logger.info("DtcConfig load completed.");
			} catch (IOException e) {
				logger.error("Load " + PROP_FILE_NAME + " failure.", e);
			}
		}
	}

	public static Long getReuseCachedSearchResultsForMillis() {
		String value = prop.getProperty("ReuseCachedSearchResultsForMillis");
		if (value == null || "0".equals(value)) {
			return null;
		}

		try {
			return Long.parseLong(value);
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
