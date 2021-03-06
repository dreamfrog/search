/*
 * Copyright 2013 Cloudera Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cloudera.cdk.morphline.avro;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;

import org.apache.avro.Schema;
import org.apache.avro.Schema.Field;
import org.apache.avro.generic.GenericContainer;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericEnumSymbol;
import org.apache.avro.generic.GenericFixed;
import org.apache.avro.generic.IndexedRecord;

import com.cloudera.cdk.morphline.api.Command;
import com.cloudera.cdk.morphline.api.CommandBuilder;
import com.cloudera.cdk.morphline.api.MorphlineContext;
import com.cloudera.cdk.morphline.api.MorphlineRuntimeException;
import com.cloudera.cdk.morphline.api.Record;
import com.cloudera.cdk.morphline.base.AbstractCommand;
import com.cloudera.cdk.morphline.base.Fields;
import com.google.common.annotations.Beta;
import com.google.common.base.Preconditions;
import com.typesafe.config.Config;


/**
 * Command that converts an attached Avro datum to a morphline record by recursively walking the
 * Avro tree and extracting all data into a single morphline record, with fields named by their path
 * in the Avro tree.
 * 
 * The Avro input object is expected to be contained in the {@link Fields#ATTACHMENT_BODY}
 * 
 * This kind of mapping is useful for simple Avro schemas, but a rather simplistic (and perhaps
 * expensive) approach for complex Avro schemas.
 * 
 * Example output:
 * 
 * <pre>
    assertEquals(Arrays.asList("us", "gb"), record.get("/name/language/country"));
    assertEquals(Arrays.asList("en-us", "en", "en-gb"), record.get("/name/language/code"));
    assertEquals(Arrays.asList(20, 40, 60), record.get("/links/forward"));
    assertEquals(Arrays.asList("http://A", "http://B"), record.get("/name/url"));
    assertEquals(Arrays.asList(10), record.get("/docId"));
 * </pre>
 * 
 * STATUS: EXPERIMENTAL
 */
@Beta
public final class ExtractAvroTreeBuilder implements CommandBuilder {
  
  @Override
  public Collection<String> getNames() {
    return Collections.singletonList("extractAvroTree");
  }
  
  @Override
  public Command build(Config config, Command parent, Command child, MorphlineContext context) {
    return new ExtractAvroTree(config, parent, child, context);
  }
  
  
  ///////////////////////////////////////////////////////////////////////////////
  // Nested classes:
  ///////////////////////////////////////////////////////////////////////////////
  private static final class ExtractAvroTree extends AbstractCommand {

    private final String outputFieldPrefix;
    
    public ExtractAvroTree(Config config, Command parent, Command child, MorphlineContext context) {
      super(config, parent, child, context);
      this.outputFieldPrefix = getConfigs().getString(config, "outputFieldPrefix", "");
      validateArguments();
    }
    
    @Override
    protected boolean doProcess(Record inputRecord) {
//      Preconditions.checkState(ReadAvroBuilder.AVRO_MEMORY_MIME_TYPE.equals(inputRecord.getFirstValue(Fields.ATTACHMENT_MIME_TYPE)));
      GenericContainer datum = (GenericContainer) inputRecord.getFirstValue(Fields.ATTACHMENT_BODY);
      Preconditions.checkNotNull(datum);
      Preconditions.checkNotNull(datum.getSchema());      
      Record outputRecord = inputRecord.copy();
      
      extractTree(datum, datum.getSchema(), outputRecord, outputFieldPrefix);
        
      // pass record to next command in chain:
      return getChild().process(outputRecord);
    }
    
    /**
     * Writes the given Avro datum into the given record, using the given Avro schema
     */
    private void extractTree(Object datum, Schema schema, Record outputRecord, String prefix) {
      // RECORD, ENUM, ARRAY, MAP, UNION, FIXED, STRING, BYTES, INT, LONG, FLOAT,
      // DOUBLE, BOOLEAN, NULL
      switch (schema.getType()) {
      case RECORD: {
        IndexedRecord avroRecord = (IndexedRecord) datum;
        String prefix2 = prefix + "/";
        for (Field field : schema.getFields()) {
          extractTree(avroRecord.get(field.pos()), field.schema(), outputRecord, prefix2 + field.name());
        }
        break;
      }
      case ENUM: {
        GenericEnumSymbol symbol = (GenericEnumSymbol) datum;
        outputRecord.put(prefix, symbol.toString());
        break;
      }
      case ARRAY: {
        Iterator iter = ((Collection) datum).iterator();
        while (iter.hasNext()) {
          extractTree(iter.next(), schema.getElementType(), outputRecord, prefix);
        }
        break;
      }
      case MAP: {
        Map<CharSequence, ?> map = (Map<CharSequence, ?>) datum;
        for (Map.Entry<CharSequence, ?> entry : map.entrySet()) {
          extractTree(entry.getValue(), schema.getValueType(), outputRecord, prefix + "/" + entry.getKey().toString());
        }
        break;
      }
      case UNION: {
        int index = GenericData.get().resolveUnion(schema, datum);
        //String typeName = schema.getTypes().get(index).getName();
        //String prefix2 = prefix + "/" + typeName;
        String prefix2 = prefix;
        extractTree(datum, schema.getTypes().get(index), outputRecord, prefix2);
        break;
      }
      case FIXED: {
        GenericFixed fixed = (GenericFixed) datum;
        outputRecord.put(prefix, fixed.bytes());
        //outputRecord.put(prefix, utf8toString(fixed.bytes()));
        break;
      }
      case BYTES: {
        ByteBuffer buf = (ByteBuffer) datum;
        int pos = buf.position();
        byte[] bytes = new byte[buf.remaining()];
        buf.get(bytes);
        buf.position(pos); // undo relative read
        outputRecord.put(prefix, bytes);
        //outputRecord.put(prefix, utf8toString(bytes));
        break;
      }
      case STRING: {
        outputRecord.put(prefix, datum.toString());
        break;
      }
      case INT: {
        outputRecord.put(prefix, datum);
        break;
      }
      case LONG: {
        outputRecord.put(prefix, datum);
        break;
      }
      case FLOAT: {
        outputRecord.put(prefix, datum);
        break;
      }
      case DOUBLE: {
        outputRecord.put(prefix, datum);
        break;
      }
      case BOOLEAN: {
        outputRecord.put(prefix, datum);
        break;
      }
      case NULL: {
        break;
      }
      default:
        throw new MorphlineRuntimeException("Unknown Avro schema type: " + schema.getType());
      }
    }

  }
  
}
