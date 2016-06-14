/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.coreedge.raft.replication;

import io.netty.buffer.ByteBuf;

import java.io.IOException;
import java.util.UUID;

import org.neo4j.coreedge.raft.net.CoreReplicatedContentMarshal;
import org.neo4j.coreedge.raft.replication.session.GlobalSession;
import org.neo4j.coreedge.raft.replication.session.LocalOperationId;
import org.neo4j.coreedge.server.CoreMember;
import org.neo4j.storageengine.api.ReadableChannel;
import org.neo4j.storageengine.api.WritableChannel;

/**
 * A uniquely identifiable operation.
 */
public class  DistributedOperation implements ReplicatedContent
{
    private final ReplicatedContent content;
    private final GlobalSession<CoreMember> globalSession;
    private final LocalOperationId operationId;

    public DistributedOperation( ReplicatedContent content, GlobalSession<CoreMember> globalSession, LocalOperationId operationId )
    {
        this.content = content;
        this.globalSession = globalSession;
        this.operationId = operationId;
    }

    public GlobalSession<CoreMember> globalSession()
    {
        return globalSession;
    }

    public LocalOperationId operationId()
    {
        return operationId;
    }

    public ReplicatedContent content()
    {
        return content;
    }

    public void serialize( WritableChannel channel ) throws IOException
    {
        channel.putLong( globalSession().sessionId().getMostSignificantBits() );
        channel.putLong( globalSession().sessionId().getLeastSignificantBits() );
        new CoreMember.CoreMemberMarshal().marshal( globalSession().owner(), channel );

        channel.putLong( operationId.localSessionId() );
        channel.putLong( operationId.sequenceNumber() );

        new CoreReplicatedContentMarshal().marshal( content, channel );
    }

    public static DistributedOperation deserialize( ReadableChannel channel ) throws IOException
    {
        long mostSigBits = channel.getLong();
        long leastSigBits = channel.getLong();
        CoreMember owner = new CoreMember.CoreMemberMarshal().unmarshal( channel );
        GlobalSession<CoreMember> globalSession = new GlobalSession<>( new UUID( mostSigBits, leastSigBits ), owner );

        long localSessionId = channel.getLong();
        long sequenceNumber = channel.getLong();
        LocalOperationId localOperationId = new LocalOperationId( localSessionId, sequenceNumber );

        ReplicatedContent content = new CoreReplicatedContentMarshal().unmarshal( channel );
        return new DistributedOperation( content, globalSession, localOperationId );
    }

    @Override
    public String toString()
    {
        return "DistributedOperation{" +
               "content=" + content +
               ", globalSession=" + globalSession +
               ", operationId=" + operationId +
               '}';
    }
}
