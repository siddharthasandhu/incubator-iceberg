/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iceberg;

import com.google.common.base.Preconditions;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.apache.avro.Schema;
import org.apache.avro.generic.IndexedRecord;
import org.apache.avro.specific.SpecificData;
import org.apache.avro.util.Utf8;
import org.apache.iceberg.avro.AvroSchemaUtil;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;

class PartitionData
    implements IndexedRecord, StructLike, SpecificData.SchemaConstructable, Serializable {

  static Schema partitionDataSchema(Types.StructType partitionType) {
    return AvroSchemaUtil.convert(partitionType, PartitionData.class.getName());
  }

  private final Types.StructType partitionType;
  private final int size;
  private final Object[] data;
  private final String stringSchema;
  private transient Schema schema = null;

  /**
   * Used by Avro reflection to instantiate this class when reading manifest files.
   */
  PartitionData(Schema schema) {
    this.partitionType = AvroSchemaUtil.convert(schema).asNestedType().asStructType();
    this.size = partitionType.fields().size();
    this.data = new Object[size];
    this.stringSchema = schema.toString();
    this.schema = schema;
  }

  PartitionData(Types.StructType partitionType) {
    for (Types.NestedField field : partitionType.fields()) {
      Preconditions.checkArgument(field.type().isPrimitiveType(),
          "Partitions cannot contain nested types: %s", field.type());
    }

    this.partitionType = partitionType;
    this.size = partitionType.fields().size();
    this.data = new Object[size];
    this.schema = partitionDataSchema(partitionType);
    this.stringSchema = schema.toString();
  }

  /**
   * Copy constructor
   */
  private PartitionData(PartitionData toCopy) {
    this.partitionType = toCopy.partitionType;
    this.size = toCopy.size;
    this.data = copyData(toCopy.partitionType, toCopy.data);
    this.stringSchema = toCopy.stringSchema;
    this.schema = toCopy.schema;
  }

  public Types.StructType getPartitionType() {
    return partitionType;
  }

  @Override
  public Schema getSchema() {
    if (schema == null) {
      this.schema = new Schema.Parser().parse(stringSchema);
    }
    return schema;
  }

  public Type getType(int pos) {
    return partitionType.fields().get(pos).type();
  }

  public void clear() {
    Arrays.fill(data, null);
  }

  @Override
  public int size() {
    return size;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> T get(int pos, Class<T> javaClass) {
    Object value = get(pos);
    if (value == null || javaClass.isInstance(value)) {
      return javaClass.cast(value);
    }

    throw new IllegalArgumentException(String.format(
        "Wrong class, %s, for object: %s",
        javaClass.getName(), String.valueOf(value)));
  }

  @Override
  public Object get(int pos) {
    if (pos >= data.length) {
      return null;
    }

    if (data[pos] instanceof byte[]) {
      return ByteBuffer.wrap((byte[]) data[pos]);
    }

    return data[pos];
  }

  @Override
  public <T> void set(int pos, T value) {
    if (value instanceof Utf8) {
      // Utf8 is not Serializable
      data[pos] = value.toString();
    } else if (value instanceof ByteBuffer) {
      // ByteBuffer is not Serializable
      ByteBuffer buffer = (ByteBuffer) value;
      byte[] bytes = new byte[buffer.remaining()];
      buffer.duplicate().get(bytes);
      data[pos] = bytes;
    } else {
      data[pos] = value;
    }
  }

  @Override
  public void put(int i, Object v) {
    set(i, v);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("PartitionData{");
    for (int i = 0; i < data.length; i += 1) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(partitionType.fields().get(i).name())
          .append("=")
          .append(data[i]);
    }
    sb.append("}");
    return sb.toString();
  }

  public PartitionData copy() {
    return new PartitionData(this);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    } else if (!(o instanceof PartitionData)) {
      return false;
    }

    PartitionData that = (PartitionData) o;
    return partitionType.equals(that.partitionType) && Arrays.equals(data, that.data);
  }

  @Override
  public int hashCode() {
    Hasher hasher = Hashing.goodFastHash(32).newHasher();
    Stream.of(data).map(Objects::hashCode).forEach(hasher::putInt);
    partitionType.fields().stream().map(Objects::hashCode).forEach(hasher::putInt);
    return hasher.hash().hashCode();
  }

  public static Object[] copyData(Types.StructType type, Object[] data) {
    List<Types.NestedField> fields = type.fields();
    Object[] copy = new Object[data.length];
    for (int i = 0; i < data.length; i += 1) {
      if (data[i] == null) {
        copy[i] = null;
      } else {
        Types.NestedField field = fields.get(i);
        switch (field.type().typeId()) {
          case STRUCT:
          case LIST:
          case MAP:
            throw new IllegalArgumentException("Unsupported type in partition data: " + type);
          case BINARY:
          case FIXED:
            byte[] buffer = (byte[]) data[i];
            copy[i] = Arrays.copyOf(buffer, buffer.length);
            break;
          case STRING:
            copy[i] = data[i].toString();
            break;
          default:
            // no need to copy the object
            copy[i] = data[i];
        }
      }
    }

    return copy;
  }
}
