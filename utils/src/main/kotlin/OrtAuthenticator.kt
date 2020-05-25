/*
 * Copyright (C) 2020 Bosch.IO GmbH
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
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package org.ossreviewtoolkit.utils

import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector

/**
 * An authenticator for network connections etablished by ORT. For proxy authentication, the [OrtProxySelector] is
 * required to also be installed.
 */
class OrtAuthenticator(private val original: Authenticator? = null) : Authenticator() {
    companion object {
        private fun getDefaultAuthenticator(): Authenticator? {
            // The getDefault() method is only available as of Java 9, see
            // https://docs.oracle.com/javase/9/docs/api/java/net/Authenticator.html#getDefault--
            val method = runCatching { Authenticator::class.java.getMethod("getDefault") }.getOrNull()
            return method?.invoke(null) as? Authenticator
        }

        /**
         * Install this authenticator as the global default.
         */
        @Synchronized
        fun install(): OrtAuthenticator {
            val current = getDefaultAuthenticator()
            return if (current is OrtAuthenticator) {
                log.info { "Authenticator is already installed." }
                current
            } else {
                OrtAuthenticator(current).also {
                    setDefault(it)
                    log.info { "Authenticator was successfully installed." }
                }
            }
        }

        /**
         * Uninstall this authenticator, restoring the previous authenticator as the global default.
         */
        @Synchronized
        fun uninstall(): Authenticator? {
            val current = getDefaultAuthenticator()
            return if (current is OrtAuthenticator) {
                current.original.also {
                    setDefault(it)
                    log.info { "Authenticator was successfully uninstalled." }
                }
            } else {
                log.info { "Authenticator is not installed." }
                current
            }
        }
    }

    override fun getPasswordAuthentication(): PasswordAuthentication? {
        if (requestorType == RequestorType.PROXY) {
            val proxySelector = ProxySelector.getDefault()
            if (proxySelector is OrtProxySelector) {
                val type = requestingProtocol.toProxyType() ?: return null
                val proxy = Proxy(type, InetSocketAddress(requestingHost, requestingPort))
                return proxySelector.getProxyAuthentication(proxy)
            }
        }

        return super.getPasswordAuthentication()
    }
}