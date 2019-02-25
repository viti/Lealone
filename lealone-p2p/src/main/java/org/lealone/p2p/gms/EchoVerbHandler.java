/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lealone.p2p.gms;

import org.lealone.common.logging.Logger;
import org.lealone.common.logging.LoggerFactory;
import org.lealone.p2p.net.IVerbHandler;
import org.lealone.p2p.net.MessageIn;
import org.lealone.p2p.net.MessageOut;
import org.lealone.p2p.net.MessagingService;
import org.lealone.p2p.net.Verb;

public class EchoVerbHandler implements IVerbHandler<EchoMessage> {
    private static final Logger logger = LoggerFactory.getLogger(EchoVerbHandler.class);

    @Override
    public void doVerb(MessageIn<EchoMessage> message, int id) {
        MessageOut<EchoMessage> echoMessage = new MessageOut<>(Verb.REQUEST_RESPONSE, new EchoMessage());
        if (logger.isTraceEnabled())
            logger.trace("Sending a EchoMessage reply {}", message.from);
        MessagingService.instance().sendReply(echoMessage, id, message.from);
    }
}
