/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.search.vectorhighlight;

import java.util.Iterator;
import java.util.Set;
import org.apache.lucene.internal.hppc.CharHashSet;

/**
 * Simple boundary scanner implementation that divides fragments based on a set of separator
 * characters.
 */
public class SimpleBoundaryScanner implements BoundaryScanner {

  public static final int DEFAULT_MAX_SCAN = 20;
  public static final char[] DEFAULT_BOUNDARY_CHARS = {'.', ',', '!', '?', ' ', '\t', '\n'};

  protected int maxScan;
  protected CharHashSet boundaryChars;

  public SimpleBoundaryScanner() {
    this(DEFAULT_MAX_SCAN, DEFAULT_BOUNDARY_CHARS);
  }

  public SimpleBoundaryScanner(int maxScan) {
    this(maxScan, DEFAULT_BOUNDARY_CHARS);
  }

  public SimpleBoundaryScanner(Character[] boundaryChars) {
    this(DEFAULT_MAX_SCAN, boundaryChars);
  }

  public SimpleBoundaryScanner(int maxScan, char[] boundaryChars) {
    this.maxScan = maxScan;
    this.boundaryChars = CharHashSet.from(boundaryChars);
  }

  public SimpleBoundaryScanner(int maxScan, Character[] boundaryChars) {
    this(maxScan, toCharArray(boundaryChars));
  }

  public SimpleBoundaryScanner(int maxScan, Set<Character> boundaryChars) {
    this(maxScan, toCharArray(boundaryChars));
  }

  private static char[] toCharArray(Character[] characters) {
    char[] chars = new char[characters.length];
    for (int i = 0; i < characters.length; i++) {
      chars[i] = characters[i];
    }
    return chars;
  }

  private static char[] toCharArray(Set<Character> characters) {
    Iterator<Character> iterator = characters.iterator();
    char[] chars = new char[characters.size()];
    for (int i = 0; i < chars.length; i++) {
      chars[i] = iterator.next();
    }
    return chars;
  }

  @Override
  public int findStartOffset(StringBuilder buffer, int start) {
    // avoid illegal start offset
    if (start > buffer.length() || start < 1) return start;
    int offset, count = maxScan;
    for (offset = start; offset > 0 && count > 0; count--) {
      // found?
      if (boundaryChars.contains(buffer.charAt(offset - 1))) return offset;
      offset--;
    }
    // if we scanned up to the start of the text, return it, it's a "boundary"
    if (offset == 0) {
      return 0;
    }
    // not found
    return start;
  }

  @Override
  public int findEndOffset(StringBuilder buffer, int start) {
    // avoid illegal start offset
    if (start > buffer.length() || start < 0) return start;
    int offset, count = maxScan;
    // for( offset = start; offset <= buffer.length() && count > 0; count-- ){
    for (offset = start; offset < buffer.length() && count > 0; count--) {
      // found?
      if (boundaryChars.contains(buffer.charAt(offset))) return offset;
      offset++;
    }
    // not found
    return start;
  }
}
