// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.remote.client;

import com.google.common.base.CharMatcher;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.Iterables;
import com.google.common.escape.CharEscaperBuilder;
import com.google.common.escape.Escaper;
import java.util.List;

/**
 * Utility class to escape strings for use with shell commands.
 *
 * <p>The code for this class was based on
 * src/main/java/com/google/devtools/build/lib/util/ShellEscaper.java from the Bazel codebase.
 *
 * <p>Escaped strings may safely be inserted into shell commands. Escaping is only done if
 * necessary. Strings containing only shell-neutral characters will not be escaped.
 */
public class ShellEscaper extends Escaper {
  public static final ShellEscaper INSTANCE = new ShellEscaper();

  private static final Function<String, String> AS_FUNCTION = INSTANCE.asFunction();

  private static final Joiner SPACE_JOINER = Joiner.on(' ');
  private static final Escaper STRONGQUOTE_ESCAPER =
      new CharEscaperBuilder().addEscape('\'', "'\\''").toEscaper();
  private static final CharMatcher SAFECHAR_MATCHER =
      CharMatcher.anyOf("@%-_+:,./")
          .or(CharMatcher.inRange('0', '9')) // We can't use CharMatcher.javaLetterOrDigit(),
          .or(CharMatcher.inRange('a', 'z')) // that would also accept non-ASCII digits and
          .or(CharMatcher.inRange('A', 'Z')) // letters.
          .precomputed();

  /**
   * Escape an argument so that it can passed as a single argument in bash command line. Unless the
   * argument contains no special characters, it will be wrapped in single quotes to escape special
   * behaviour. In the case that the arguments itself has single quotes, the inner single quotes are
   * escaped as a special case to avoid conflicting with the out single quotes.
   */
  public String escape(String unescaped) {
    final String s = unescaped.toString();
    if (s.isEmpty()) {
      // Empty string is a special case: needs to be quoted to ensure that it
      // gets treated as a separate argument.
      return "''";
    } else {
      return SAFECHAR_MATCHER.matchesAllOf(s) ? s : "'" + STRONGQUOTE_ESCAPER.escape(s) + "'";
    }
  }

  public static String escapeString(String unescaped) {
    return INSTANCE.escape(unescaped);
  }

  /**
   * Returns a string containing the argument strings in the given list escaped and joined by
   * spaces.
   */
  public static String escapeJoinAll(List<String> args) {
    return SPACE_JOINER.join(Iterables.transform(args, AS_FUNCTION));
  }
}
