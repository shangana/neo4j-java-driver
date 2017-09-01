/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
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
package org.neo4j.driver.internal.handlers;

import io.netty.util.concurrent.Promise;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.neo4j.driver.internal.InternalRecord;
import org.neo4j.driver.internal.netty.AsyncConnection;
import org.neo4j.driver.internal.netty.InternalListenableFuture;
import org.neo4j.driver.internal.netty.ListenableFuture;
import org.neo4j.driver.internal.spi.ResponseHandler;
import org.neo4j.driver.internal.summary.InternalNotification;
import org.neo4j.driver.internal.summary.InternalPlan;
import org.neo4j.driver.internal.summary.InternalProfiledPlan;
import org.neo4j.driver.internal.summary.InternalSummaryCounters;
import org.neo4j.driver.v1.Record;
import org.neo4j.driver.v1.Value;
import org.neo4j.driver.v1.summary.Notification;
import org.neo4j.driver.v1.summary.Plan;
import org.neo4j.driver.v1.summary.ProfiledPlan;
import org.neo4j.driver.v1.summary.StatementType;
import org.neo4j.driver.v1.summary.SummaryCounters;

public class RecordsResponseHandler implements ResponseHandler
{
    private final StatementKeysAccessor keysAccessor;
    private final AsyncConnection asyncConnection;

    // todo: all these fields are written by an event loop thread and read by user thread.
    // todo: accesses should have correct synchronization!
    private final Queue<Record> recordBuffer;
    private Promise<Boolean> recordAvailablePromise;

    private StatementType statementType;
    private SummaryCounters counters;
    private Plan plan;
    private ProfiledPlan profile;
    private List<Notification> notifications;
    private long resultConsumedAfter;

    // todo: maybe use single queue for records, failures and completion signals???
    // todo: for async allocate a promise for result summary right away???
    private boolean completed;
    private Throwable failure;

    public RecordsResponseHandler( StatementKeysAccessor keysAccessor )
    {
        this( keysAccessor, null );
    }

    public RecordsResponseHandler( StatementKeysAccessor keysAccessor, AsyncConnection asyncConnection )
    {
        this.keysAccessor = keysAccessor;
        this.asyncConnection = asyncConnection;
        this.recordBuffer = new ConcurrentLinkedQueue<>();
    }

    @Override
    public void onSuccess( Map<String,Value> metadata )
    {
        statementType = extractStatementType( metadata );
        counters = extractCounters( metadata );
        plan = extractPlan( metadata );
        profile = extractProfiledPlan( metadata );
        notifications = extractNotifications( metadata );
        resultConsumedAfter = extractResultConsumedAfter( metadata );

        markCompleted();

        if ( recordAvailablePromise != null )
        {
            boolean hasMoreRecords = !recordBuffer.isEmpty();
            recordAvailablePromise.setSuccess( hasMoreRecords );
            recordAvailablePromise = null;
        }
    }

    @Override
    public void onFailure( Throwable error )
    {
        markCompleted();
        failure = error;

        if ( recordAvailablePromise != null )
        {
            recordAvailablePromise.setFailure( error );
            recordAvailablePromise = null;
        }
    }

    @Override
    public void onRecord( Value[] fields )
    {
        recordBuffer.add( new InternalRecord( keysAccessor.statementKeys(), fields ) );

        if ( recordAvailablePromise != null )
        {
            recordAvailablePromise.setSuccess( true );
            recordAvailablePromise = null;
        }
    }

    public ListenableFuture<Boolean> recordAvailable()
    {
        final Promise<Boolean> resultPromise = asyncConnection.newPromise();

        asyncConnection.executeInEventLoop( new Runnable()
        {
            @Override
            public void run()
            {
                if ( !recordBuffer.isEmpty() )
                {
                    resultPromise.setSuccess( true );
                }
                else if ( failure != null )
                {
                    resultPromise.setFailure( failure );
                }
                else if ( completed )
                {
                    resultPromise.setSuccess( false );
                }
                else
                {
                    recordAvailablePromise = resultPromise;
                }
            }
        } );

        return new InternalListenableFuture<>( resultPromise );
    }

    public Queue<Record> recordBuffer()
    {
        return recordBuffer;
    }

    public StatementType statementType()
    {
        return statementType;
    }

    public SummaryCounters counters()
    {
        return counters;
    }

    public Plan plan()
    {
        return plan;
    }

    public ProfiledPlan profile()
    {
        return profile;
    }

    public List<Notification> notifications()
    {
        return notifications;
    }

    public long resultConsumedAfter()
    {
        return resultConsumedAfter;
    }

    public boolean isCompleted()
    {
        return completed;
    }

    private void markCompleted()
    {
        completed = true;
        if ( asyncConnection != null ) // todo: this null check is only needed for sync connections
        {
            asyncConnection.release();
        }
    }

    private static StatementType extractStatementType( Map<String,Value> metadata )
    {
        Value typeValue = metadata.get( "type" );
        if ( typeValue != null )
        {
            return StatementType.fromCode( typeValue.asString() );
        }
        return null;
    }

    private static InternalSummaryCounters extractCounters( Map<String,Value> metadata )
    {
        Value countersValue = metadata.get( "stats" );
        if ( countersValue != null )
        {
            return new InternalSummaryCounters(
                    counterValue( countersValue, "nodes-created" ),
                    counterValue( countersValue, "nodes-deleted" ),
                    counterValue( countersValue, "relationships-created" ),
                    counterValue( countersValue, "relationships-deleted" ),
                    counterValue( countersValue, "properties-set" ),
                    counterValue( countersValue, "labels-added" ),
                    counterValue( countersValue, "labels-removed" ),
                    counterValue( countersValue, "indexes-added" ),
                    counterValue( countersValue, "indexes-removed" ),
                    counterValue( countersValue, "constraints-added" ),
                    counterValue( countersValue, "constraints-removed" )
            );
        }
        return null;
    }

    private static int counterValue( Value countersValue, String name )
    {
        Value value = countersValue.get( name );
        return value.isNull() ? 0 : value.asInt();
    }

    private static Plan extractPlan( Map<String,Value> metadata )
    {
        Value planValue = metadata.get( "plan" );
        if ( planValue != null )
        {
            return InternalPlan.EXPLAIN_PLAN_FROM_VALUE.apply( planValue );
        }
        return null;
    }

    private static ProfiledPlan extractProfiledPlan( Map<String,Value> metadata )
    {
        Value profiledPlanValue = metadata.get( "profile" );
        if ( profiledPlanValue != null )
        {
            return InternalProfiledPlan.PROFILED_PLAN_FROM_VALUE.apply( profiledPlanValue );
        }
        return null;
    }

    private static List<Notification> extractNotifications( Map<String,Value> metadata )
    {
        Value notificationsValue = metadata.get( "notifications" );
        if ( notificationsValue != null )
        {
            return notificationsValue.asList( InternalNotification.VALUE_TO_NOTIFICATION );
        }
        return Collections.emptyList();
    }

    private static long extractResultConsumedAfter( Map<String,Value> metadata )
    {
        Value resultConsumedAfterValue = metadata.get( "result_consumed_after" );
        if ( resultConsumedAfterValue != null )
        {
            return resultConsumedAfterValue.asLong();
        }
        return -1;
    }
}