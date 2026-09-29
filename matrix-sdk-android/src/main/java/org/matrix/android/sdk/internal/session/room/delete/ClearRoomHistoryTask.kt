/*
 * Copyright 2022 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.session.room.delete

import com.zhuinden.monarchy.Monarchy
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.internal.database.model.EventEntity
import org.matrix.android.sdk.internal.database.model.EventEntityFields
import org.matrix.android.sdk.internal.database.model.TimelineEventEntity
import org.matrix.android.sdk.internal.database.model.TimelineEventEntityFields
import org.matrix.android.sdk.internal.database.model.deleteOnCascade
import org.matrix.android.sdk.internal.database.query.whereRoomId
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.session.room.delete.ClearRoomHistoryTask.Params
import org.matrix.android.sdk.internal.task.Task
import org.matrix.android.sdk.internal.util.awaitTransaction
import timber.log.Timber
import javax.inject.Inject

internal interface ClearRoomHistoryTask : Task<Params, Unit> {
    data class Params(
            val roomId: String,
            val olderThanTimestampMs: Long? = null,
    )
}

internal class DefaultClearRoomHistoryTask @Inject constructor(
        @SessionDatabase private val monarchy: Monarchy,
) : ClearRoomHistoryTask {

    override suspend fun execute(params: Params) {
        val roomId = params.roomId
        val olderThanTimestampMs = params.olderThanTimestampMs
        monarchy.awaitTransaction { realm ->
            Timber.i("## ClearRoomHistoryTask - clear messages in room $roomId olderThan=$olderThanTimestampMs")

            val timelineQuery = TimelineEventEntity.whereRoomId(realm, roomId)
                    .isNull(TimelineEventEntityFields.ROOT.STATE_KEY)
            if (olderThanTimestampMs != null) {
                timelineQuery.lessThan(TimelineEventEntityFields.ROOT.ORIGIN_SERVER_TS, olderThanTimestampMs)
            }
            val timelineEvents = timelineQuery.findAll()
            Timber.i("## ClearRoomHistoryTask - TimelineEventEntity - delete ${timelineEvents.size} entries")
            // Copy to list first: cascading deletes mutate the live results
            timelineEvents.toList().forEach { it.deleteOnCascade(true) }

            val messageTypes = arrayOf(
                    EventType.MESSAGE,
                    EventType.ENCRYPTED,
                    EventType.STICKER,
                    EventType.REACTION,
            )
            val orphanQuery = EventEntity.whereRoomId(realm, roomId)
                    .isNull(EventEntityFields.STATE_KEY)
                    .`in`(EventEntityFields.TYPE, messageTypes)
            if (olderThanTimestampMs != null) {
                orphanQuery.lessThan(EventEntityFields.ORIGIN_SERVER_TS, olderThanTimestampMs)
            }
            val orphanEvents = orphanQuery.findAll()
            Timber.i("## ClearRoomHistoryTask - orphan EventEntity - delete ${orphanEvents.size} entries")
            orphanEvents.deleteAllFromRealm()
        }
    }
}
