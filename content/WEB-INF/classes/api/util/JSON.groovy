// Copyright (c) 2021 MintJams Inc. Licensed under MIT License.

package api.util;

import com.fasterxml.jackson.databind.ObjectMapper;

class JSON {
	static Object parse(Object value) {
		if (value == null) {
			return null;
		}

		if (value instanceof String) {
			if (value.startsWith("{")) {
				return new ObjectMapper().readValue(value, java.util.Map.class);
			}
			if (value.startsWith("[")) {
				return new ObjectMapper().readValue(value, java.util.List.class);
			}
			if (value.startsWith("\"")) {
				return new ObjectMapper().readValue(value, java.lang.String.class);
			}
			throw new IllegalArgumentException("Could not parse JSON text: " + value);
		}

		if (value instanceof java.io.InputStream) {
			return value.withCloseable { vin ->
				return parse(vin.getText("UTF-8"));
			}
		}

		if (value instanceof java.io.Reader) {
			return value.withCloseable { vin ->
				return parse(vin.text);
			}
		}

		if (value instanceof org.mintjams.script.resource.Resource) {
			return value.getContentAsReader().withCloseable { reader ->
				return parse(reader.text);
			}
		}

		if (value instanceof File) {
			return parse(value.getText("UTF-8"));
		}

		throw new IllegalArgumentException("Could not parse JSON text: Class: " + value.getClass().getName());
	}

	static String stringify(Object value) {
		return new ObjectMapper().writeValueAsString(value);
	}
}
