package io.libp2p.guava.common.math;

/*
 * Copyright (C) 2011 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */


import io.libp2p.guava.common.primitives.Ints;
import java.math.BigInteger;

/**
 * A class for arithmetic on values of type {@code int}. Where possible, methods are defined and
 * named analogously to their {@code BigInteger} counterparts.
 *
 * <p>The implementations of many methods in this class are based on material from Henry S. Warren,
 * Jr.'s <i>Hacker's Delight</i>, (Addison Wesley, 2002).
 *
 * <p>Similar functionality for {@code long} and for {@link BigInteger} can be found in
 * LongMath and BigIntegerMath respectively. For other common operations on {@code int}
 * values, see {@link com.google.common.primitives.Ints}.
 *
 * @author Louis Wasserman
 * @since 11.0
 */
public final class IntMath {

    public static int saturatedMultiply(int a, int b) {
        return Ints.saturatedCast((long) a * b);
    }

    private IntMath() {}
}
