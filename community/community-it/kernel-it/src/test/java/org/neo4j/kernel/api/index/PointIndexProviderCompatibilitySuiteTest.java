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
package org.neo4j.kernel.api.index;

import java.nio.file.Path;

import org.neo4j.configuration.Config;
import org.neo4j.configuration.helpers.DatabaseReadOnlyChecker;
import org.neo4j.index.internal.gbptree.RecoveryCleanupWorkCollector;
import org.neo4j.internal.schema.IndexPrototype;
import org.neo4j.internal.schema.IndexType;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.io.pagecache.tracing.PageCacheTracer;
import org.neo4j.kernel.impl.index.schema.PointIndexProviderFactory;
import org.neo4j.monitoring.Monitors;

import static org.neo4j.configuration.GraphDatabaseSettings.DEFAULT_DATABASE_NAME;
import static org.neo4j.internal.schema.SchemaDescriptors.forLabel;

class PointIndexProviderCompatibilitySuiteTest extends SpecialisedIndexProviderCompatibilityTestSuite
{

    @Override
    IndexPrototype indexPrototype()
    {
        return IndexPrototype.forSchema( forLabel( 1000, 100 ) ).withIndexType( IndexType.POINT );
    }

    @Override
    IndexType indexType()
    {
        return IndexType.POINT;
    }

    @Override
    IndexProvider createIndexProvider( PageCache pageCache, FileSystemAbstraction fs, Path graphDbDir, Config config )
    {
        Monitors monitors = new Monitors();
        String monitorTag = "";
        RecoveryCleanupWorkCollector recoveryCleanupWorkCollector = RecoveryCleanupWorkCollector.immediate();
        var readOnlyChecker = new DatabaseReadOnlyChecker.Default( config, DEFAULT_DATABASE_NAME );
        return PointIndexProviderFactory.create( pageCache, graphDbDir, fs, monitors, monitorTag, config, readOnlyChecker, recoveryCleanupWorkCollector,
                PageCacheTracer.NULL, DEFAULT_DATABASE_NAME );
    }
}
