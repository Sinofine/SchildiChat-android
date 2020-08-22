/*
 * Copyright (c) 2020 New Vector Ltd
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

package im.vector.app.features.widgets.permissions

import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.integrationmanager.IntegrationManagerService
import org.matrix.android.sdk.api.session.widgets.WidgetService
import org.matrix.android.sdk.internal.util.awaitCallback

class WidgetPermissionsHelper(private val integrationManagerService: IntegrationManagerService,
                              private val widgetService: WidgetService) {

    suspend fun changePermission(roomId: String, widgetId: String, allow: Boolean) {
        val widget = widgetService.getRoomWidgets(
                roomId = roomId,
                widgetId = QueryStringValue.Equals(widgetId, QueryStringValue.Case.SENSITIVE)
        ).firstOrNull()
        val eventId = widget?.event?.eventId ?: return
        awaitCallback<Unit> {
            integrationManagerService.setWidgetAllowed(eventId, allow, it)
        }
    }
}
