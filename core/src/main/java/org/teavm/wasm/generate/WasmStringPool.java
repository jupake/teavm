/*
 *  Copyright 2016 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.teavm.wasm.generate;

import java.util.HashMap;
import java.util.Map;
import org.teavm.model.ValueType;
import org.teavm.wasm.binary.BinaryWriter;
import org.teavm.wasm.binary.DataArray;
import org.teavm.wasm.binary.DataPrimitives;
import org.teavm.wasm.binary.DataStructure;
import org.teavm.wasm.binary.DataValue;

public class WasmStringPool {
    private WasmClassGenerator classGenerator;
    private BinaryWriter binaryWriter;
    private Map<String, Integer> stringMap = new HashMap<>();
    private DataStructure arrayHeaderType = new DataStructure((byte) 0, DataPrimitives.INT, DataPrimitives.INT);
    private DataStructure stringType = new DataStructure((byte) 0,
            DataPrimitives.INT, /* class pointer */
            DataPrimitives.ADDRESS, /* monitor */
            DataPrimitives.ADDRESS, /* characters */
            DataPrimitives.INT /* hash code */);

    public WasmStringPool(WasmClassGenerator classGenerator, BinaryWriter binaryWriter) {
        this.classGenerator = classGenerator;
        this.binaryWriter = binaryWriter;
    }

    public int getStringPointer(String value) {
        return stringMap.computeIfAbsent(value, str -> {
            DataArray charactersType = new DataArray(DataPrimitives.SHORT, str.length());
            DataStructure wrapperType = new DataStructure((byte) 0, arrayHeaderType, charactersType);
            DataValue wrapper = wrapperType.createValue();
            DataValue header = wrapper.getValue(0);
            DataValue characters = wrapper.getValue(1);

            header.setInt(0, classGenerator.getClassPointer(ValueType.arrayOf(ValueType.CHARACTER)));
            header.setInt(1, str.length());
            for (int i = 0; i < str.length(); ++i) {
                characters.setShort(i, (short) str.charAt(i));
            }

            DataValue stringObject = stringType.createValue();
            int stringPointer = binaryWriter.append(stringObject);
            stringObject.setInt(0, classGenerator.getClassPointer(ValueType.object(String.class.getName())));
            stringObject.setAddress(2, binaryWriter.append(wrapper));

            return stringPointer;
        });
    }
}
