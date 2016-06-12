/*
 * Copyright 2016 higherfrequencytrading.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.chronicle.wire;

import junit.framework.TestCase;
import net.openhft.chronicle.bytes.Bytes;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;

/**
 * @author Rob Austin.
 */
@RunWith(value = Parameterized.class)
public class ValueOutTest extends TestCase {

    private final WireType wireType;

    public ValueOutTest(WireType wireType) {
        this.wireType = wireType;
    }

    @Parameterized.Parameters
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{
                {WireType.TEXT},
                {WireType.BINARY}
        });
    }

    @Test
    public void test() {

        final Wire wire = wireType.apply(Bytes.elasticByteBuffer());
        assert wire.startUse();
        final byte[] expected = "this is my byte array".getBytes();
        wire.writeDocument(false, w ->
                w.write().object(expected)

        );

        System.out.println(Wires.fromSizePrefixedBlobs(wire.bytes()));

        wire.readDocument(null, w -> {
            final byte[] actual = (byte[]) w.read().object();
            Assert.assertArrayEquals(expected, actual);

        });

    }

    @Test
    public void testRequestedType() {

        final Wire wire = wireType.apply(Bytes.elasticByteBuffer());
        assert wire.startUse();
        final byte[] expected = "this is my byte array".getBytes();
        wire.writeDocument(false, w -> w.write().object(expected));

        System.out.println(Wires.fromSizePrefixedBlobs(wire.bytes()));

        wire.readDocument(null, w -> {
            final byte[] actual = w.read().object(byte[].class);
            Assert.assertArrayEquals(expected, actual);
        });

    }

    @Test
    public void testAllBytes() {

        final Wire wire = wireType.apply(Bytes.elasticByteBuffer());
        assert wire.startUse();
        for (int i = -128; i < 127; i++) {

            final byte[] expected = {(byte) i};
            wire.writeDocument(false, w ->
                    w.write().object(expected)
            );

            System.out.println(Wires.fromSizePrefixedBlobs(wire.bytes()));

            wire.readDocument(null, w -> {
                final byte[] actual = (byte[]) w.read().object();
                Assert.assertArrayEquals(expected, actual);
            });

        }
    }
}
