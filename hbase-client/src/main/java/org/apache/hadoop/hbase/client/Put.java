/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hbase.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.Tag;
import org.apache.hadoop.hbase.io.HeapSize;
import org.apache.hadoop.hbase.util.Bytes;

/**
 * Used to perform Put operations for a single row.
 * <p>
 * To perform a Put, instantiate a Put object with the row to insert to and
 * for each column to be inserted, execute {@link #add(byte[], byte[], byte[]) add} or
 * {@link #add(byte[], byte[], long, byte[]) add} if setting the timestamp.
 */
@InterfaceAudience.Public
@InterfaceStability.Stable
public class Put extends Mutation implements HeapSize, Comparable<Row> {
  /**
   * Create a Put operation for the specified row.
   * @param row row key
   */
  public Put(byte [] row) {
    this(row, HConstants.LATEST_TIMESTAMP);
  }

  /**
   * Create a Put operation for the specified row, using a given timestamp.
   *
   * @param row row key; we make a copy of what we are passed to keep local.
   * @param ts timestamp
   */
  public Put(byte[] row, long ts) {
    this(row, 0, row.length, ts);
  }

  /**
   * We make a copy of the passed in row key to keep local.
   * @param rowArray
   * @param rowOffset
   * @param rowLength
   */
  public Put(byte [] rowArray, int rowOffset, int rowLength) {
    this(rowArray, rowOffset, rowLength, HConstants.LATEST_TIMESTAMP);
  }

  /**
   * We make a copy of the passed in row key to keep local.
   * @param rowArray
   * @param rowOffset
   * @param rowLength
   * @param ts
   */
  public Put(byte [] rowArray, int rowOffset, int rowLength, long ts) {
    checkRow(rowArray, rowOffset, rowLength);
    this.row = Bytes.copy(rowArray, rowOffset, rowLength);
    this.ts = ts;
    if (ts < 0) {
      throw new IllegalArgumentException("Timestamp cannot be negative. ts=" + ts);
    }
  }

  /**
   * Copy constructor.  Creates a Put operation cloned from the specified Put.
   * @param putToCopy put to copy
   */
  public Put(Put putToCopy) {
    this(putToCopy.getRow(), putToCopy.ts);
    this.familyMap = new TreeMap<byte [], List<Cell>>(Bytes.BYTES_COMPARATOR);
    for(Map.Entry<byte [], List<Cell>> entry: putToCopy.getFamilyCellMap().entrySet()) {
      this.familyMap.put(entry.getKey(), entry.getValue());
    }
    this.durability = putToCopy.durability;
    for (Map.Entry<String, byte[]> entry : putToCopy.getAttributesMap().entrySet()) {
      this.setAttribute(entry.getKey(), entry.getValue());
    }
  }

  /**
   * Add the specified column and value to this Put operation.
   * @param family family name
   * @param qualifier column qualifier
   * @param value column value
   * @return this
   */
  public Put add(byte [] family, byte [] qualifier, byte [] value) {
    return add(family, qualifier, this.ts, value);
  }

  public Put add(byte[] family, byte [] qualifier, byte [] value, Tag[] tag) {
    return add(family, qualifier, this.ts, value, tag);
  }

  /**
   * Add the specified column and value, with the specified timestamp as
   * its version to this Put operation.
   * @param family family name
   * @param qualifier column qualifier
   * @param ts version timestamp
   * @param value column value
   * @return this
   */
  public Put add(byte [] family, byte [] qualifier, long ts, byte [] value) {
    if (ts < 0) {
      throw new IllegalArgumentException("Timestamp cannot be negative. ts=" + ts);
    }
    List<Cell> list = getCellList(family);
    KeyValue kv = createPutKeyValue(family, qualifier, ts, value);
    list.add(kv);
    familyMap.put(family, list);
    return this;
  }

  /**
   * Forms a keyvalue with tags
   */
  @SuppressWarnings("unchecked")
  public Put add(byte[] family, byte[] qualifier, long ts, byte[] value, Tag[] tag) {
    List<Cell> list = getCellList(family);
    KeyValue kv = createPutKeyValue(family, qualifier, ts, value, tag);
    list.add(kv);
    familyMap.put(kv.getFamily(), list);
    return this;
  }

  /**
   * Add the specified KeyValue to this Put operation.  Operation assumes that
   * the passed KeyValue is immutable and its backing array will not be modified
   * for the duration of this Put.
   * @param kv individual KeyValue
   * @return this
   * @throws java.io.IOException e
   */
  public Put add(Cell kv) throws IOException{
    byte [] family = CellUtil.cloneFamily(kv);
    List<Cell> list = getCellList(family);
    //Checking that the row of the kv is the same as the put
    int res = Bytes.compareTo(this.row, 0, row.length,
        kv.getRowArray(), kv.getRowOffset(), kv.getRowLength());
    if (res != 0) {
      throw new WrongRowIOException("The row in " + kv.toString() +
        " doesn't match the original one " +  Bytes.toStringBinary(this.row));
    }
    list.add(kv);
    familyMap.put(family, list);
    return this;
  }

  /**
   * A convenience method to determine if this object's familyMap contains
   * a value assigned to the given family & qualifier.
   * Both given arguments must match the KeyValue object to return true.
   *
   * @param family column family
   * @param qualifier column qualifier
   * @return returns true if the given family and qualifier already has an
   * existing KeyValue object in the family map.
   */
  public boolean has(byte [] family, byte [] qualifier) {
  return has(family, qualifier, this.ts, new byte[0], true, true);
  }

  /**
   * A convenience method to determine if this object's familyMap contains
   * a value assigned to the given family, qualifier and timestamp.
   * All 3 given arguments must match the KeyValue object to return true.
   *
   * @param family column family
   * @param qualifier column qualifier
   * @param ts timestamp
   * @return returns true if the given family, qualifier and timestamp already has an
   * existing KeyValue object in the family map.
   */
  public boolean has(byte [] family, byte [] qualifier, long ts) {
  return has(family, qualifier, ts, new byte[0], false, true);
  }

  /**
   * A convenience method to determine if this object's familyMap contains
   * a value assigned to the given family, qualifier and timestamp.
   * All 3 given arguments must match the KeyValue object to return true.
   *
   * @param family column family
   * @param qualifier column qualifier
   * @param value value to check
   * @return returns true if the given family, qualifier and value already has an
   * existing KeyValue object in the family map.
   */
  public boolean has(byte [] family, byte [] qualifier, byte [] value) {
    return has(family, qualifier, this.ts, value, true, false);
  }

  /**
   * A convenience method to determine if this object's familyMap contains
   * the given value assigned to the given family, qualifier and timestamp.
   * All 4 given arguments must match the KeyValue object to return true.
   *
   * @param family column family
   * @param qualifier column qualifier
   * @param ts timestamp
   * @param value value to check
   * @return returns true if the given family, qualifier timestamp and value
   * already has an existing KeyValue object in the family map.
   */
  public boolean has(byte [] family, byte [] qualifier, long ts, byte [] value) {
      return has(family, qualifier, ts, value, false, false);
  }

  /*
   * Private method to determine if this object's familyMap contains
   * the given value assigned to the given family, qualifier and timestamp
   * respecting the 2 boolean arguments
   *
   * @param family
   * @param qualifier
   * @param ts
   * @param value
   * @param ignoreTS
   * @param ignoreValue
   * @return returns true if the given family, qualifier timestamp and value
   * already has an existing KeyValue object in the family map.
   */
  private boolean has(byte[] family, byte[] qualifier, long ts, byte[] value,
                      boolean ignoreTS, boolean ignoreValue) {
    List<Cell> list = getCellList(family);
    if (list.size() == 0) {
      return false;
    }
    // Boolean analysis of ignoreTS/ignoreValue.
    // T T => 2
    // T F => 3 (first is always true)
    // F T => 2
    // F F => 1
    if (!ignoreTS && !ignoreValue) {
      for (Cell cell : list) {
        if (CellUtil.matchingFamily(cell, family) &&
            CellUtil.matchingQualifier(cell, qualifier)  &&
            CellUtil.matchingValue(cell, value) &&
            cell.getTimestamp() == ts) {
          return true;
        }
      }
    } else if (ignoreValue && !ignoreTS) {
      for (Cell cell : list) {
        if (CellUtil.matchingFamily(cell, family) && CellUtil.matchingQualifier(cell, qualifier)
            && cell.getTimestamp() == ts) {
          return true;
        }
      }
    } else if (!ignoreValue && ignoreTS) {
      for (Cell cell : list) {
        if (CellUtil.matchingFamily(cell, family) && CellUtil.matchingQualifier(cell, qualifier)
            && CellUtil.matchingValue(cell, value)) {
          return true;
        }
      }
    } else {
      for (Cell cell : list) {
        if (CellUtil.matchingFamily(cell, family) &&
            CellUtil.matchingQualifier(cell, qualifier)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Returns a list of all KeyValue objects with matching column family and qualifier.
   *
   * @param family column family
   * @param qualifier column qualifier
   * @return a list of KeyValue objects with the matching family and qualifier,
   * returns an empty list if one doesn't exist for the given family.
   */
  public List<Cell> get(byte[] family, byte[] qualifier) {
    List<Cell> filteredList = new ArrayList<Cell>();
    for (Cell cell: getCellList(family)) {
      if (CellUtil.matchingQualifier(cell, qualifier)) {
        filteredList.add(cell);
      }
    }
    return filteredList;
  }
}
