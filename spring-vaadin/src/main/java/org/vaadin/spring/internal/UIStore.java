/*
 * Copyright 2014 The original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.vaadin.spring.internal;


import com.vaadin.server.ClientConnector;
import com.vaadin.ui.UI;
import com.vaadin.util.CurrentInstance;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.util.Assert;
import org.springframework.web.context.request.RequestContextHolder;

import java.io.Serializable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serializable storage for all UI scoped beans. The idea is to have one {@code UIStore} stored in each session.
 * Thus, when the session is deserialized, all UI scoped beans and destruction callbacks should also be deserialzied.
 *
 * @author Petter Holmström (petter@vaadin.com)
 */
class UIStore implements Serializable, ClientConnector.DetachListener {

    private final Log logger = LogFactory.getLog(getClass());
    private final Map<VaadinUIIdentifier, Map<String, Object>> objectMap = new ConcurrentHashMap<>();
    private final Map<VaadinUIIdentifier, Map<String, Runnable>> destructionCallbackMap = new ConcurrentHashMap<>();

    public VaadinUIIdentifier currentUiId() {
        final UI currentUI = UI.getCurrent();
        if (currentUI != null) {
            return new VaadinUIIdentifier(currentUI);
        } else {
            VaadinUIIdentifier currentIdentifier = CurrentInstance.get(VaadinUIIdentifier.class);
            Assert.notNull(currentIdentifier, String.format("Found no valid %s instance!", VaadinUIIdentifier.class.getName()));
            return currentIdentifier;
        }
    }

    public String currentSessionId() {
        return RequestContextHolder.currentRequestAttributes().getSessionId();
    }

    public String getConversationId() {
        return currentSessionId() + currentUiId();
    }

    public Object get(String name, ObjectFactory<?> objectFactory) {
        return get(name, objectFactory, currentUiId());
    }

    public Object get(String name, ObjectFactory<?> objectFactory, VaadinUIIdentifier uiId) {
        logger.debug("Getting bean with name [" + name + "] from UI space [" + uiId + "]");
        final Map<String, Object> uiSpace = getObjectMap(uiId);
        Object bean = uiSpace.get(name);
        if (bean == null) {
            logger.debug(String.format("Bean [%s] not found in UI space [%s], invoking object factory", name, uiId));
            bean = objectFactory.getObject();
            if (bean instanceof UI) {
                ((UI) bean).addDetachListener(this);
            }
            if (!(bean instanceof Serializable)) {
                logger.warn(String.format("Storing non-serializable bean [%s] with name [%s] in UI space [%s]", bean, name, uiId));
            }
            uiSpace.put(name, bean);
        }
        logger.debug(String.format("Returning bean [%s] with name [%s] from UI space [%s]", bean, name, uiId));
        return bean;
    }

    public Object remove(String name) {
        return remove(name, currentUiId());
    }

    public Object remove(String name, VaadinUIIdentifier uiId) {
        logger.debug(String.format("Removing bean with name [%s] from UI space [%s]", name, uiId));
        try {
            getDestructionCallbackMap(uiId).remove(name);
            return getObjectMap(uiId).remove(name);
        } finally {
            cleanEmptyMaps(uiId);
        }
    }

    public void registerDestructionCallback(String name, Runnable callback) {
        registerDestructionCallback(name, callback, currentUiId());
    }

    public void registerDestructionCallback(String name, Runnable callback, VaadinUIIdentifier uiId) {
        logger.debug(String.format("Registering destruction callback [%s] for bean with name [%s] in UI space [%s]", callback, name, uiId));
        if (!(callback instanceof Serializable)) {
            logger.warn(String.format("Storing non-serializable destruction callback [%s] for bean with name [%s] in UI space [%s]", callback, name, uiId));
        }
        getDestructionCallbackMap(uiId).put(name, callback);
    }

    private Map<String, Object> getObjectMap(VaadinUIIdentifier uiId) {
        Map<String, Object> map = objectMap.get(uiId);
        if (map == null) {
            map = new ConcurrentHashMap<>();
            objectMap.put(uiId, map);
        }
        return map;
    }

    private void cleanEmptyMaps(VaadinUIIdentifier uiId) {
        Map<String, Object> uiSpace = objectMap.get(uiId);
        if (uiSpace != null && uiSpace.isEmpty()) {
            objectMap.remove(uiId);
        }
        Map<String, Runnable> destructionCallbacks = destructionCallbackMap.get(uiId);
        if (destructionCallbacks != null && destructionCallbacks.isEmpty()) {
            destructionCallbacks.remove(uiId);
        }
    }

    private Map<String, Runnable> getDestructionCallbackMap(VaadinUIIdentifier uiId) {
        Map<String, Runnable> map = destructionCallbackMap.get(uiId);
        if (map == null) {
            map = new ConcurrentHashMap<>();
            destructionCallbackMap.put(uiId, map);
        }
        return map;
    }

    @Override
    public void detach(ClientConnector.DetachEvent event) {
        logger.debug(String.format("Received DetachEvent from [%s]", event.getSource()));
        final VaadinUIIdentifier uiIdentifier = new VaadinUIIdentifier((UI) event.getSource());
        final Map<String, Runnable> destructionSpace = destructionCallbackMap.remove(uiIdentifier);
        if (destructionSpace != null) {
            for (Runnable runnable : destructionSpace.values()) {
                runnable.run();
            }
        }
        objectMap.remove(uiIdentifier);
    }
}
