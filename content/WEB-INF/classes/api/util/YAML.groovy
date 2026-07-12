// Copyright (c) 2021 MintJams Inc. Licensed under MIT License.

package api.util;

import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

class YAML {
	static Object parse(Object value) {
		if (value == null) {
			throw new IllegalArgumentException("Could not parse YAML text: null");
		}

		if (value instanceof String) {
			return new Load(LoadSettings.builder().build()).loadFromString(value);
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

		throw new IllegalArgumentException("Could not parse YAML text: Class: " + value.getClass().getName());
	}

	static String stringify(Object value) {
		return new Dump(DumpSettings.builder().build()).dumpToString(value);
	}
}
