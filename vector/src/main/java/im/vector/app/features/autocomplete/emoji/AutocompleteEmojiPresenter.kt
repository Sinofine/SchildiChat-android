/*
 * Copyright 2019 New Vector Ltd
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

package im.vector.app.features.autocomplete.emoji

import android.content.Context
import androidx.recyclerview.widget.RecyclerView
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.R
import im.vector.app.features.autocomplete.AutocompleteClickListener
import im.vector.app.features.autocomplete.RecyclerViewPresenter
import im.vector.app.features.autocomplete.member.AutocompleteEmojiDataItem
import im.vector.app.features.reactions.data.EmojiDataSource
import im.vector.app.features.reactions.data.EmojiItem
import im.vector.app.features.settings.VectorPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.query.QueryStateEventValue
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.accountdata.UserAccountDataTypes
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.room.Room
import org.matrix.android.sdk.api.session.room.getStateEvent
import org.matrix.android.sdk.api.session.room.model.RoomEmoteContent
import kotlin.math.min

class AutocompleteEmojiPresenter @AssistedInject constructor(
        context: Context,
        @Assisted val roomId: String,
        private val session: Session,
        private val vectorPreferences: VectorPreferences,
        private val emojiDataSource: EmojiDataSource,
        private val controller: AutocompleteEmojiController
) :
        RecyclerViewPresenter<EmojiItem>(context), AutocompleteClickListener<EmojiItem> {

    private val room by lazy { session.getRoom(roomId)!! }

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        controller.listener = this
    }

    fun clear() {
        coroutineScope.coroutineContext.cancelChildren()
        controller.listener = null
    }

    @AssistedFactory
    interface Factory {
        fun create(roomId: String): AutocompleteEmojiPresenter
    }

    override fun instantiateAdapter(): RecyclerView.Adapter<*> {
        return controller.adapter
    }

    override fun onItemClick(t: EmojiItem) {
        dispatchClick(t)
    }

    override fun onQuery(query: CharSequence?) {
        coroutineScope.launch {
            // Plain emojis
            val data = if (query.isNullOrBlank()) {
                // Return common emojis
                emojiDataSource.getQuickReactions()
            } else {
                emojiDataSource.filterWith(query.toString())
            }.toAutocompleteItems()

            // Custom emotes: This room's emotes
            val currentRoomEmotes = room.getAllEmojiItems(query)
            val emoteData = currentRoomEmotes.toAutocompleteItems().let {
                if (it.isNotEmpty()) {
                    listOf(AutocompleteEmojiDataItem.Header(roomId, context.getString(R.string.custom_emotes_this_room))) + it
                } else {
                    emptyList()
                }
            }.limit(AutocompleteEmojiController.CUSTOM_THIS_ROOM_MAX).toMutableList()
            val emoteUrls = HashSet<String>()
            emoteUrls.addAll(currentRoomEmotes.map { it.mxcUrl })
            // Global emotes (only while searching)
            if (!query.isNullOrBlank()) {
                // Account emotes
                val userPack = session.accountDataService().getUserAccountDataEvent(UserAccountDataTypes.TYPE_USER_EMOTES)?.content
                        ?.toModel<RoomEmoteContent>().getEmojiItems(query)
                        .limit(AutocompleteEmojiController.CUSTOM_ACCOUNT_MAX)
                if (userPack.isNotEmpty()) {
                    emoteUrls.addAll(userPack.map { it.mxcUrl })
                    emoteData += listOf(
                            AutocompleteEmojiDataItem.Header(
                                    "de.spiritcroc.riotx.ACCOUNT_EMOJI_HEADER",
                                    context.getString(R.string.custom_emotes_account_data)
                            )
                    )
                    emoteData += userPack.toAutocompleteItems()
                }
                // Global emotes from rooms
                val globalPacks = session.accountDataService().getUserAccountDataEvent(UserAccountDataTypes.TYPE_EMOTE_ROOMS)
                var packsAdded = 0
                (globalPacks?.content?.get("rooms") as? Map<*, *>)?.forEach { pack ->
                    // If entry is empty, it has been disabled as global pack (after being enabled before).
                    val packsEnabled = pack.value as? Map<*, *>
                    if (packsEnabled.isNullOrEmpty()) {
                        return@forEach
                    }
                    if (packsAdded >= AutocompleteEmojiController.MAX_CUSTOM_OTHER_ROOMS) {
                        return@forEach
                    }
                    val packRoomId = pack.key
                    if (packRoomId is String && packRoomId != roomId) {
                        val packRoom = session.getRoom(packRoomId) ?: return@forEach
                        packsEnabled.forEach roomPack@{ roomPack ->
                            val packId = roomPack.key as? String ?: return@roomPack
                            val emojiItems = packRoom.getEmojiItems(query, QueryStringValue.Equals(packId))
                            val packName = emojiItems.first
                            // Filter out duplicate emotes with the exact same mxc url
                            val packImages = emojiItems.second.filter {
                                it.mxcUrl !in emoteUrls
                            }.limit(AutocompleteEmojiController.CUSTOM_OTHER_ROOM_MAX)
                            // Add header + emotes
                            if (packImages.isNotEmpty()) {
                                packsAdded++
                                emoteUrls.addAll(packImages.map { it.mxcUrl })
                                val packRoomName = packRoom.roomSummary()?.displayName ?: packRoomId
                                roomPack.value
                                emoteData += listOf(
                                        AutocompleteEmojiDataItem.Header(
                                                packRoomId,
                                                if (packName != null) {
                                                    context.getString(
                                                            R.string.custom_emotes_named_other_room,
                                                            packName,
                                                            packRoomName
                                                    )
                                                } else {
                                                    context.getString(
                                                            R.string.custom_emotes_other_room,
                                                            packRoomName
                                                    )
                                                }
                                        )
                                )
                                emoteData += packImages.toAutocompleteItems()
                            }
                        }
                    }
                }
            }

            val dataHeader = if (data.isNotEmpty() && emoteData.isNotEmpty()) {
                listOf(AutocompleteEmojiDataItem.Header("de.spiritcroc.riotx.STANDARD_EMOJI_HEADER", context.getString(R.string.standard_emojis)))
            } else {
                emptyList()
            }
            controller.setData(emoteData + dataHeader + data)
        }
    }

    private fun List<EmojiItem>.toAutocompleteItems(): List<AutocompleteEmojiDataItem> {
        return map { AutocompleteEmojiDataItem.Emoji(it) }
    }

    private fun Room.getEmojiItems(query: CharSequence?, queryStringValue: QueryStateEventValue): Pair<String?, List<EmojiItem>> {
        val content = getStateEvent(EventType.ROOM_EMOTES, queryStringValue)?.content?.toModel<RoomEmoteContent>()
        val packName = content?.pack?.displayName
        return Pair(packName, content.getEmojiItems(query))
    }

    private fun Room.getAllEmojiItems(query: CharSequence?): List<EmojiItem> {
        // NoCondition isn't allowed by matrix sdk for getStateEvents, so query both empty and non-empty
        val eventTypeList = setOf(EventType.ROOM_EMOTES)
        val emptyItems = stateService().getStateEvents(eventTypeList, QueryStringValue.IsEmpty)
        val keyedItems = stateService().getStateEvents(eventTypeList, QueryStringValue.IsNotEmpty)
        return (emptyItems + keyedItems).flatMap {
            it.content?.toModel<RoomEmoteContent>().getEmojiItems(query)
        }
    }

    private fun RoomEmoteContent?.getEmojiItems(query: CharSequence?): List<EmojiItem> {
        return this?.images.orEmpty()
                .filter {
                    val usages = it.value.usage
                    usages.isNullOrEmpty() || RoomEmoteContent.USAGE_EMOTICON in usages
                }.filter {
                    query == null || it.key.contains(query, true)
                }.map {
                    EmojiItem(it.key, "", mxcUrl = it.value.url)
                }.sortedBy { it.name }.distinctBy { it.mxcUrl }
    }

    private fun <T>List<T>.limit(count: Int): List<T> {
        return subList(0, min(count, size))
    }
}
