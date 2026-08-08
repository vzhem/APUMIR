package com.vladimir.messenger.service

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BotApiEntryPoint {
    fun botApi(): BotApi
}
