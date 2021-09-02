/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.index.schema;

import org.neo4j.index.internal.gbptree.Writer;
import org.neo4j.io.IOUtils;
import org.neo4j.kernel.api.exceptions.index.IndexEntryConflictException;
import org.neo4j.kernel.api.index.IndexUpdater;
import org.neo4j.storageengine.api.IndexEntryUpdate;
import org.neo4j.storageengine.api.ValueIndexEntryUpdate;
import org.neo4j.values.storable.Value;

import static org.neo4j.kernel.impl.index.schema.NativeIndexKey.Inclusion.NEUTRAL;

class NativeIndexUpdater<KEY extends NativeIndexKey<KEY>> implements IndexUpdater
{
    private final KEY treeKey;
    private final IndexUpdateIgnoreStrategy ignoreStrategy;
    private final ConflictDetectingValueMerger<KEY,Value[]> conflictDetectingValueMerger = new ThrowingConflictDetector<>( true );
    private Writer<KEY,NullValue> writer;

    private boolean closed = true;

    NativeIndexUpdater( KEY treeKey, IndexUpdateIgnoreStrategy ignoreStrategy )
    {
        this.treeKey = treeKey;
        this.ignoreStrategy = ignoreStrategy;
    }

    NativeIndexUpdater<KEY> initialize( Writer<KEY,NullValue> writer )
    {
        if ( !closed )
        {
            throw new IllegalStateException( "Updater still open" );
        }

        this.writer = writer;
        closed = false;
        return this;
    }

    @Override
    public void process( IndexEntryUpdate<?> update ) throws IndexEntryConflictException
    {
        assertOpen();
        ValueIndexEntryUpdate<?> valueUpdate = asValueUpdate( update );
        if ( ignoreStrategy.ignore( valueUpdate ) )
        {
            return;
        }
        processUpdate( treeKey, valueUpdate, writer, conflictDetectingValueMerger );
    }

    @Override
    public void close()
    {
        closed = true;
        IOUtils.closeAllUnchecked( writer );
    }

    private void assertOpen()
    {
        if ( closed )
        {
            throw new IllegalStateException( "Updater has been closed" );
        }
    }

    static <KEY extends NativeIndexKey<KEY>> void processUpdate( KEY treeKey,
            ValueIndexEntryUpdate<?> update, Writer<KEY,NullValue> writer, ConflictDetectingValueMerger<KEY,Value[]> conflictDetectingValueMerger )
            throws IndexEntryConflictException
    {
        switch ( update.updateMode() )
        {
        case ADDED:
            processAdd( treeKey, update, writer, conflictDetectingValueMerger );
            break;
        case CHANGED:
            processChange( treeKey, update, writer, conflictDetectingValueMerger );
            break;
        case REMOVED:
            processRemove( treeKey, update, writer );
            break;
        default:
            throw new IllegalArgumentException();
        }
    }

    private static <KEY extends NativeIndexKey<KEY>> void processRemove( KEY treeKey,
            ValueIndexEntryUpdate<?> update, Writer<KEY,NullValue> writer )
    {
        // todo Do we need to verify that we actually removed something at all?
        // todo Difference between online and recovery?
        initializeKeyFromUpdate( treeKey, update.getEntityId(), update.values() );
        writer.remove( treeKey );
    }

    private static <KEY extends NativeIndexKey<KEY>> void processChange( KEY treeKey,
            ValueIndexEntryUpdate<?> update, Writer<KEY,NullValue> writer,
            ConflictDetectingValueMerger<KEY,Value[]> conflictDetectingValueMerger )
            throws IndexEntryConflictException
    {
        // Remove old entry
        initializeKeyFromUpdate( treeKey, update.getEntityId(), update.beforeValues() );
        writer.remove( treeKey );
        // Insert new entry
        initializeKeyFromUpdate( treeKey, update.getEntityId(), update.values() );
        conflictDetectingValueMerger.controlConflictDetection( treeKey );
        writer.merge( treeKey, NullValue.INSTANCE, conflictDetectingValueMerger );
        conflictDetectingValueMerger.checkConflict( update.values() );
    }

    private static <KEY extends NativeIndexKey<KEY>> void processAdd( KEY treeKey,
            ValueIndexEntryUpdate<?> update, Writer<KEY,NullValue> writer, ConflictDetectingValueMerger<KEY,Value[]> conflictDetectingValueMerger )
            throws IndexEntryConflictException
    {
        initializeKeyFromUpdate( treeKey, update.getEntityId(), update.values() );
        conflictDetectingValueMerger.controlConflictDetection( treeKey );
        writer.merge( treeKey, NullValue.INSTANCE, conflictDetectingValueMerger );
        conflictDetectingValueMerger.checkConflict( update.values() );
    }

    static <KEY extends NativeIndexKey<KEY>> void initializeKeyFromUpdate( KEY treeKey, long entityId, Value[] values )
    {
        treeKey.initialize( entityId );
        for ( int i = 0; i < values.length; i++ )
        {
            treeKey.initFromValue( i, values[i], NEUTRAL );
        }
    }
}
