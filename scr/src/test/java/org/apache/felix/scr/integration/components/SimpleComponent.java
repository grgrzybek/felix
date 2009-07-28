/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.felix.scr.integration.components;


import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import org.osgi.framework.Constants;
import org.osgi.service.component.ComponentContext;


public class SimpleComponent
{

    public static SimpleComponent INSTANCE;

    public static final Map<String, SimpleComponent> INSTANCES = new HashMap<String, SimpleComponent>();

    private Map<?, ?> m_config;


    @SuppressWarnings("unused")
    private void activate( Map<?, ?> config )
    {
        INSTANCE = this;
        INSTANCES.put( (String) config.get( Constants.SERVICE_PID), this );
        setConfig( config );
    }


    @SuppressWarnings("unused")
    private void configure( ComponentContext context )
    {
        setConfig( context.getProperties() );
    }


    @SuppressWarnings("unused")
    private void deactivate()
    {
        INSTANCES.remove( getProperty( Constants.SERVICE_PID ));
        INSTANCE = null;
    }


    protected void setConfig( Map<?, ?> config )
    {
        m_config = config;
    }


    protected void setConfig( Dictionary<?, ?> config )
    {
        Map<Object, Object> configMap = new HashMap<Object, Object>();
        for ( Enumeration<?> ce = config.elements(); ce.hasMoreElements(); )
        {
            Object key = ce.nextElement();
            Object value = config.get( key );
            configMap.put( key, value );
        }
        m_config = configMap;
    }


    public Object getProperty( Object name )
    {
        return m_config.get( name );
    }
}
