/*
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
package io.prestosql.orc.reader;

import io.prestosql.memory.context.LocalMemoryContext;
import io.prestosql.orc.OrcColumn;
import io.prestosql.orc.OrcCorruptionException;
import io.prestosql.orc.TupleDomainFilter;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.RunLengthEncodedBlock;
import io.prestosql.spi.block.ShortArrayBlock;
import io.prestosql.spi.type.SmallintType;
import io.prestosql.spi.type.Type;
import org.openjdk.jol.info.ClassLayout;

import java.io.IOException;
import java.util.Optional;

import static com.google.common.base.Verify.verify;
import static io.airlift.slice.SizeOf.sizeOf;
import static io.prestosql.orc.reader.ReaderUtils.minNonNullValueSize;
import static io.prestosql.orc.reader.ReaderUtils.unpackShortNulls;

public class ShortColumnReader
        extends AbstractNumericColumnReader<Short>
{
    private static final int INSTANCE_SIZE = ClassLayout.parseClass(ShortColumnReader.class).instanceSize();

    private short[] shortNonNullValueTemp = new short[0];

    /**
     * FIXME: KEN: why do we need to pass in type? isn't it implied already?
     * @param column
     * @param systemMemoryContext
     * @throws OrcCorruptionException
     */
    public ShortColumnReader(Type type, OrcColumn column, LocalMemoryContext systemMemoryContext)
            throws OrcCorruptionException
    {
        super(type, column, systemMemoryContext);
    }

    @Override
    public Block<Short> readBlock()
            throws IOException
    {
        if (!rowGroupOpen) {
            openRowGroup();
        }

        if (readOffset > 0) {
            if (presentStream != null) {
                // skip ahead the present bit reader, but count the set bits
                // and use this as the skip size for the data reader
                readOffset = presentStream.countBitsSet(readOffset);
            }
            if (readOffset > 0) {
                if (dataStream == null) {
                    throw new OrcCorruptionException(column.getOrcDataSourceId(), "Value is not null but data stream is missing");
                }
                dataStream.skip(readOffset);
            }
        }

        Block block;
        if (dataStream == null) {
            if (presentStream == null) {
                throw new OrcCorruptionException(column.getOrcDataSourceId(), "Value is null but present stream is missing");
            }
            presentStream.skip(nextBatchSize);
            block = RunLengthEncodedBlock.create(SmallintType.SMALLINT, null, nextBatchSize);
        }
        else if (presentStream == null) {
            block = readNonNullBlock();
        }
        else {
            boolean[] isNull = new boolean[nextBatchSize];
            int nullCount = presentStream.getUnsetBits(nextBatchSize, isNull);
            if (nullCount == 0) {
                block = readNonNullBlock();
            }
            else if (nullCount != nextBatchSize) {
                block = readNullBlock(isNull, nextBatchSize - nullCount);
            }
            else {
                block = RunLengthEncodedBlock.create(SmallintType.SMALLINT, null, nextBatchSize);
            }
        }

        readOffset = 0;
        nextBatchSize = 0;

        return block;
    }

    private Block readNonNullBlock()
            throws IOException
    {
        verify(dataStream != null);
        short[] values = new short[nextBatchSize];
        dataStream.next(values, nextBatchSize);
        return new ShortArrayBlock(nextBatchSize, Optional.empty(), values);
    }

    private Block readNullBlock(boolean[] isNull, int nonNullCount)
            throws IOException
    {
        return shortReadNullBlock(isNull, nonNullCount);
    }

    private Block shortReadNullBlock(boolean[] isNull, int nonNullCount)
            throws IOException
    {
        verify(dataStream != null);
        int minNonNullValueSize = minNonNullValueSize(nonNullCount);
        if (shortNonNullValueTemp.length < minNonNullValueSize) {
            shortNonNullValueTemp = new short[minNonNullValueSize];
            systemMemoryContext.setBytes(sizeOf(shortNonNullValueTemp));
        }

        dataStream.next(shortNonNullValueTemp, nonNullCount);

        short[] result = unpackShortNulls(shortNonNullValueTemp, isNull);

        return new ShortArrayBlock(nextBatchSize, Optional.of(isNull), result);
    }

    @Override
    public boolean filterTest(TupleDomainFilter filter, Short value)
    {
        if (value == null) {
            return filter.testNull();
        }

        return filter.testLong(value);
    }
}
