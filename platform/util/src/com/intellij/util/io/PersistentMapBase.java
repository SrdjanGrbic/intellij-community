// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.util.io;

import com.intellij.openapi.util.io.BufferExposingByteArrayOutputStream;
import com.intellij.util.Processor;
import com.intellij.util.io.AppendablePersistentMap.ValueDataAppender;
import org.jetbrains.annotations.*;

import java.io.*;
import java.io.DataOutputStream;

/**
 * A base interface for custom persistent map implementations.
 * It is intentionally made not to extend other interfaces.
 * Wrap this class with {@link PersistentHashMap} if you need it to implement other interfaces
 * @see PersistentHashMap
 * @see PersistentMapBuilder
 */
@ApiStatus.Experimental
public interface PersistentMapBase<Key, Value> {
  @ApiStatus.OverrideOnly
  @NotNull
  DataExternalizer<Value> getValuesExternalizer();

  /**
   * Appends value chunk from specified appender to key's value.
   * Important use note: value externalizer used by this map should process all bytes from DataInput during deserialization and make sure
   * that deserialized value is consistent with value chunks appended.
   * E.g. Value can be Set of String and individual Strings can be appended with this method for particular key, when {@link #get(Object)} will
   * be eventually called for the key, deserializer will read all bytes retrieving Strings and collecting them into Set
   */
  default void appendData(Key key, @NotNull ValueDataAppender appender) throws IOException {
    BufferExposingByteArrayOutputStream bos = new BufferExposingByteArrayOutputStream();
    DataOutputStream dos = new DataOutputStream(bos);
    DataExternalizer<Value> dataExternalizer = getValuesExternalizer();

    Value oldValue = get(key);
    if (oldValue != null) {
      dataExternalizer.save(dos, oldValue);
    }
    appender.append(dos);
    dos.close();

    DataInputStream dis = new DataInputStream(bos.toInputStream());
    Value newValue = dataExternalizer.read(dis);
    dis.close();

    put(key, newValue);
  }

  /**
   * Process only existing keys in a map.
   * TODO: do we have constraint on ordering? Deterministic ordering?
   */
  boolean processExistingKeys(@NotNull Processor<? super Key> processor) throws IOException;

  /**
   * Process all keys registered in the map.
   * It several implementations it might work significantly faster then {@link PersistentMapBase#processExistingKeys(Processor)}
   * and it makes sense to prefer this method in performance critical code: completion, navigation.
   * <br>
   * Note that:
   * <ul><li>
   *  keys which were removed at some point might be returned as well.
   * </li><li>
   *  some implementations might not support it.
   * </li></ul>
   * TODO: shall we name it explicitly? Let's use default implementation for either of methods.
   * TODO: do we have constraint on ordering? Deterministic ordering?
   */
  boolean processKeys(@NotNull Processor<? super Key> processor) throws IOException;

  boolean containsKey(Key key) throws IOException;

  Value get(Key key) throws IOException;

  void put(Key key, Value value) throws IOException;

  void remove(Key key) throws IOException;

  /**
   * Returns true if storage is dirty. See {@link PersistentMapBase#markDirty()} for details.
   */
  boolean isDirty();

  /**
   * Marks storage as dirty, e.g. it has some in memory changes which are not yet written to disk
   * or write in progress.
   */
  void markDirty() throws IOException;

  /**
   * Force all transient changes to disk.
   */
  void force() throws IOException;

  boolean isClosed();

  void close() throws IOException;

  /**
   * Closes the map removing all data storages.
   * Note, that map should not be used after this method has been called.
   */
  void closeAndDelete() throws IOException;

  /**@return keys count, or -1 if implementation doesn't provide this info */
  default int keysCount(){
    return -1;
  }
}
