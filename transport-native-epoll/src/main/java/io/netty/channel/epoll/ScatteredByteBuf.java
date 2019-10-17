/*
 * Copyright 2019 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.channel.epoll;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.unix.IovArray;
import io.netty.util.AbstractReferenceCounted;
import io.netty.util.ReferenceCounted;

import java.util.Arrays;

final class ScatteredByteBuf extends AbstractReferenceCounted {

    private static final int START_SIZE_SHIFT = 6;
    private static final ByteBuf[] NULLS = new ByteBuf[10];

    private ByteBuf[] bufs;

    ScatteredByteBuf() {
        this.bufs = null;
    }

    void prepare(ByteBufAllocator alloc, IovArray iovArray, int guess) {
        if (bufs == null) {
            bufs = Arrays.copyOf(NULLS, NULLS.length);
        }
        for (int i = 0; i < bufs.length; i++) {
            if (bufs[i] == null) {
                bufs[i] = alloc.ioBuffer(1 << (i + START_SIZE_SHIFT));
            }
            iovArray.add(bufs[i], bufs[i].writerIndex(), bufs[i].capacity());
        }
    }

    ByteBuf read(int bytes) {
        int componentsToUse = (32 - START_SIZE_SHIFT) - Integer.numberOfLeadingZeros(bytes + 63);
        ByteBuf[] readable = Arrays.copyOf(bufs, componentsToUse);
        for (ByteBuf buf : readable) {
            if (bytes == 0) {
                break;
            }
            int amt = Math.min(buf.capacity(), bytes);
            buf.writerIndex(amt);
            bytes -= amt;
        }
        System.arraycopy(NULLS, 0, bufs, 0, componentsToUse);
        return Unpooled.wrappedBuffer(readable);
    }

    @Override
    protected void deallocate() {
        for (int i = 0; i < bufs.length; i++) {
            if (bufs[i] != null) {
                bufs[i].release();
            }
        }
        bufs = null;
    }

    @Override
    public ReferenceCounted touch(Object hint) {
        return this;
    }
}
