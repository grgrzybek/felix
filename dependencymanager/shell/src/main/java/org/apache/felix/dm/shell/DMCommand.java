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
package org.apache.felix.dm.shell;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.TreeSet;

import org.apache.felix.dm.Component;
import org.apache.felix.dm.ComponentDeclaration;
import org.apache.felix.dm.ComponentDependencyDeclaration;
import org.apache.felix.dm.DependencyManager;
import org.apache.felix.service.command.CommandSession;
import org.apache.felix.service.command.Descriptor;
import org.apache.felix.service.command.Parameter;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.Filter;
import org.osgi.framework.InvalidSyntaxException;

/**
 * Shell command for showing all services and dependencies that are managed
 * by the dependency manager.
 * 
 * @author <a href="mailto:dev@felix.apache.org">Felix Project Team</a>
 */
@Descriptor("Commands used to dump all existing Dependency Manager components")
public class DMCommand {
    /**
     * Bundle context used to create OSGI filters.
     */
    private final BundleContext m_context;
    
    /**
     * Sorter used to sort components.
     */
    private static final DependencyManagerSorter SORTER = new DependencyManagerSorter();

    /**
     * Constant used by the wtf command, when listing missing services.
     */
    private static final String SERVICE = "service";
    
    /**
     * Constant used by the wtf command, when listing missing configurations.
     */
    private static final String CONFIGURATION = "configuration";

    /**
     * Name of a specific gogo shell variable, which may be used to configure "compact" mode.
     * Example: g! dependencymanager.compact=true
     */
    private final static String ENV_COMPACT = "dependencymanager.compact";
    
    /**
     * Name of a specific gogo shell variable, which may be used to configure an OSGI filter, normally
     * passed to the "dm services" option. It is used to display only some service providing components
     * matching the given filter. The filter can contain an "objectClass" option.
     * Example: 
     *   g! dependencymanager.services="(protocol=http)"
     *   g! dependencymanager.services="(&(objectClass=foo.Bar)(protocol=http))"
     */
    private final static String ENV_SERVICES = "dependencymanager.services";

    /**
     * Name of a specific gogo shell variable, which may be used to configure a filter on the
     * component implementation class name.
     * The value of this shell variable may contain multiple regex (space separated), and each regex can
     * be negated using "!".
     * Example: g! dependencymanager.components="foo.bar.* ga.bu.zo.*"
     */
    private final static String ENV_COMPONENTS = "dependencymanager.components";
        
    /**
     * Constructor.
     */
    public DMCommand(BundleContext context) {
        m_context = context;
    }

    /**
     * Dependency Manager "dm" command. We use gogo annotations, in order to automate documentation,
     * and also to automatically manage optional flags/options and parameters ordering.
     * 
     * @param session the gogo command session, used to get some variables declared in the shell
     *        This parameter is automatically passed by the gogo runtime.
     * @param nodeps false means that dependencies are not displayed
     * @param compact true means informations are displayed in a compact format. This parameter can also be 
     *        set using the "dependencymanager.compact" gogo shell variable.
     * @param notavail only unregistered components / unavailable dependencies are displayed 
     * @param stats true means some statistics are displayed
     * @param services an osgi filter used to filter on some given osgi service properties.  This parameter can also be 
     *        set using the "dependencymanager.services" gogo shell variable.
     * @param components a regular expression to match either component implementation class names.  This parameter can also be 
     *        set using the "dependencymanager.components" gogo shell variable.
     * @param componentIds only components matching one of the specified components ids are displayed
     * @param bundleIds a list of bundle ids or symbolic names, used to filter on some given bundles
     */
    @Descriptor("List dependency manager components")
    public void dm(
            CommandSession session,

            @Descriptor("Hides component dependencies") 
            @Parameter(names = {"nodeps", "nd"}, presentValue = "true", absentValue = "false") 
            boolean nodeps,

            @Descriptor("Displays components using a compact form") 
            @Parameter(names = {"compact", "cp"}, presentValue = "true", absentValue = "") 
            String compact,
            
            @Descriptor("Only displays unavailable components") 
            @Parameter(names = {"notavail", "na"}, presentValue = "true", absentValue = "false") 
            boolean notavail,

            @Descriptor("Detects where are the root failures") 
            @Parameter(names = {"wtf"}, presentValue = "true", absentValue = "false") 
            boolean wtf,

            @Descriptor("Displays components statistics") 
            @Parameter(names = {"stats", "st"}, presentValue = "true", absentValue = "false") 
            boolean stats,

            @Descriptor("OSGi filter used to filter some service properties") 
            @Parameter(names = {"services", "s"}, absentValue = "") 
            String services,

            @Descriptor("Regex(s) used to filter on component implementation class names (comma separated, can be negated using \"!\" prefix)") 
            @Parameter(names = {"components", "c"}, absentValue = "") 
            String components,
            
            @Descriptor("Component identifiers to display (list of longs, comma separated)") 
            @Parameter(names = {"componentIds", "cid", "ci"}, absentValue = "") 
            String componentIds,

            @Descriptor("List of bundle ids or bundle symbolic names to display (comma separated)") 
            @Parameter(names = {"bundleIds", "bid", "bi"}, absentValue = "") 
            String bundleIds) {
        
        try {
            boolean comp = Boolean.parseBoolean(getParam(session, ENV_COMPACT, compact));
            services = getParam(session, ENV_SERVICES, services);
            String[] componentsRegex = getParams(session, ENV_COMPONENTS, components);

            ArrayList<String> bids = new ArrayList<String>(); // list of bundle ids or bundle symbolic names
            ArrayList<Long> cids = new ArrayList<Long>(); // list of component ids

            // Parse and check componentIds option
            StringTokenizer tok = new StringTokenizer(componentIds, ", ");
            while (tok.hasMoreTokens()) {
                try {
                    cids.add(Long.parseLong(tok.nextToken()));
                } catch (NumberFormatException e) {
                    System.out.println("Invalid value for componentIds option");
                    return;
                }
            }

            // Parse services fitler
            Filter servicesFilter = null;
            try {
                if (services != null) {
                    servicesFilter = m_context.createFilter(services);
                }
            } catch (InvalidSyntaxException e) {
                System.out.println("Invalid services OSGi filter: " + services);
                e.printStackTrace(System.err);
                return;
            }

            // Parse and check bundleIds option
            tok = new StringTokenizer(bundleIds, ", ");
            while (tok.hasMoreTokens()) {
                bids.add(tok.nextToken());
            }
            
            if (wtf) {
        	wtf();
        	return;
            }

            // lookup all dependency manager service components
            List<DependencyManager> managers = DependencyManager.getDependencyManagers();
            Collections.sort(managers, SORTER);
            Iterator<DependencyManager> iterator = managers.iterator();
            long numberOfComponents = 0;
            long numberOfDependencies = 0;
            long lastBundleId = -1;
            while (iterator.hasNext()) {
                DependencyManager manager = iterator.next();
                List<Component> complist = manager.getComponents();
                Iterator<Component> componentIterator = complist.iterator();
                while (componentIterator.hasNext()) {
                    Component component = componentIterator.next();
                    ComponentDeclaration sc = (ComponentDeclaration) component;
                    String name = sc.getName();
                    // check if this component is enabled or disabled.
                    if (!mayDisplay(component, servicesFilter, componentsRegex, cids)) {
                        continue;
                    }
                    int state = sc.getState();
                    Bundle bundle = sc.getBundleContext().getBundle();
                    if (matchBundle(bundle, bids)) {
                        long bundleId = bundle.getBundleId();
                        if (notavail) {
                            if (sc.getState() != ComponentDeclaration.STATE_UNREGISTERED) {
                                continue;
                            }
                        }

                        numberOfComponents++;
                        if (lastBundleId != bundleId) {
                            lastBundleId = bundleId;
                            if (comp) {
                                System.out.println("[" + bundleId + "] " + compactName(bundle.getSymbolicName()));
                            } else {
                                System.out.println("[" + bundleId + "] " + bundle.getSymbolicName());
                            }
                        }
                        if (comp) {
                            System.out.print(" [" + sc.getId() + "] " + compactName(name) + " "
                                    + compactState(ComponentDeclaration.STATE_NAMES[state]));
                        } else {
                            System.out.println(" [" + sc.getId() + "] " + name + " "
                                    + ComponentDeclaration.STATE_NAMES[state]);
                        }
                        if (!nodeps) {
                            ComponentDependencyDeclaration[] dependencies = sc.getComponentDependencies();
                            if (dependencies != null && dependencies.length > 0) {
                                numberOfDependencies += dependencies.length;
                                if (comp) {
                                    System.out.print('(');
                                }
                                for (int j = 0; j < dependencies.length; j++) {
                                    ComponentDependencyDeclaration dep = dependencies[j];
                                    if (notavail && !isUnavailable(dep)) {
                                        continue;
                                    }
                                    String depName = dep.getName();
                                    String depType = dep.getType();
                                    int depState = dep.getState();

                                    if (comp) {
                                        if (j > 0) {
                                            System.out.print(' ');
                                        }
                                        System.out.print(compactName(depName) + " " + compactState(depType) + " "
                                                + compactState(ComponentDependencyDeclaration.STATE_NAMES[depState]));
                                    } else {
                                        System.out.println("    " + depName + " " + depType + " "
                                                + ComponentDependencyDeclaration.STATE_NAMES[depState]);
                                    }
                                }
                                if (comp) {
                                    System.out.print(')');
                                }
                            }
                        }
                        if (comp) {
                            System.out.println();
                        }
                    }
                }
            }

            if (stats) {
                System.out.println("Statistics:");
                System.out.println(" - Dependency managers: " + managers.size());
                System.out.println(" - Components: " + numberOfComponents);
                if (!nodeps) {
                    System.out.println(" - Dependencies: " + numberOfDependencies);
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private boolean isUnavailable(ComponentDependencyDeclaration dep) {
        switch (dep.getState()) {
            case ComponentDependencyDeclaration.STATE_UNAVAILABLE_OPTIONAL:
            case ComponentDependencyDeclaration.STATE_UNAVAILABLE_REQUIRED:
                return true;
        }
        return false;
    }

    private boolean matchBundle(Bundle bundle, List<String> ids) {
        if (ids.size() == 0) {
            return true;
        }
        
        for (int i = 0; i < ids.size(); i ++) {
            String id = ids.get(i);
            try {
                Long longId = Long.valueOf(id);
                if (longId == bundle.getBundleId()) {
                    return true;
                }                
            } catch (NumberFormatException e) {
                // must match symbolic name
                if (id.equals(bundle.getSymbolicName())) {
                    return true;
                }
            }
        }
        
        return false;
    }

    /**
     * Returns the value of a command arg parameter, or from the gogo shell if the parameter is not passed to
     * the command.
     */
    private String getParam(CommandSession session, String param, String value) {
        if (value != null && value.length() > 0) {
            return value;
        }
        Object shellParamValue = session.get(param);
        return shellParamValue != null ? shellParamValue.toString() : null;
    }

    /**
     * Returns the value of a command arg parameter, or from the gogo shell if the parameter is not passed to
     * the command. The parameter value is meant to be a list of values separated by a blank or a comma. 
     * The values are split and returned as an array.
     */
    private String[] getParams(CommandSession session, String name, String value) {
        String values = null;
        if (value == null || value.length() == 0) {
            value = (String) session.get(name);
            if (value != null) {
                values = value;
            }
        } else {
            values = value;
        }
        if (values == null) {
            return new String[0];
        }      
        return values.trim().split(", ");
    }

    /**
     * Checks if a component can be displayed. We make a logical OR between the three following conditions:
     * 
     *  - the component service properties are matching a given service filter ("services" option)
     *  - the component implementation class name is matching some regex ("components" option)
     *  - the component declaration name is matching some regex ("names" option)
     *  
     *  If some component ids are provided, then the component must also match one of them.
     */
    private boolean mayDisplay(Component component, Filter servicesFilter, String[] components, List<Long> componentIds) {   
        // Check component id
        if (componentIds.size() > 0) {
            long componentId = ((ComponentDeclaration) component).getId();
            if (componentIds.indexOf(componentId) == -1) {
                return false;
            }
        }
        
        if (servicesFilter == null && components.length == 0) {
            return true;
        }     
        
        // Check component service properties
        boolean servicesMatches = servicesMatches(component, servicesFilter);        

        // Check components regexs, which may match component implementation class name
        boolean componentsMatches = componentMatches(((ComponentDeclaration) component).getClassName(), components);

        // Logical OR between service properties match and component service/impl match.
        return servicesMatches || componentsMatches;   
    }

    /**
     * Checks if a given filter is matching some service properties possibly provided by a component
     */
    private boolean servicesMatches(Component component, Filter servicesFilter) {
        boolean match = false;
        if (servicesFilter != null) {
            String[] services = ((ComponentDeclaration) component).getServices();
            if (services != null) {
                Dictionary<String, Object> properties = component.getServiceProperties();
                if (properties == null) {
                    properties = new Hashtable<String, Object>();
                }
                if (properties.get(Constants.OBJECTCLASS) == null) {
                    properties.put(Constants.OBJECTCLASS, services);
                }
                match = servicesFilter.match(properties);
            }
        }
        return match;
    }

    /**
     * Checks if the component implementation class name (or some possible provided services) are matching
     * some regular expressions.
     */
    private boolean componentMatches(String description, String[] names) {
        for (int i = 0; i < names.length; i ++) {
            String name = names[i];
            boolean not = false;
            if (name.startsWith("!")) {
                name = name.substring(1);
                not = true;
            }
            boolean match = false;

            if (description.matches(name)) {
                match = true;
            }
                       
            if (not) {
                match = !match;
            }
            
            if (match) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * Compact names that look like state strings. State strings consist of
     * one or more words. Each word will be shortened to the first letter,
     * all letters concatenated and uppercased.
     */
    private String compactState(String input) {
        StringBuffer output = new StringBuffer();
        StringTokenizer st = new StringTokenizer(input);
        while (st.hasMoreTokens()) {
            output.append(st.nextToken().toUpperCase().charAt(0));
        }
        return output.toString();
    }

    /**
     * Compacts names that look like fully qualified class names. All packages
     * will be shortened to the first letter, except for the last one. So
     * something like "org.apache.felix.MyClass" will become "o.a.f.MyClass".
     */
    private String compactName(String input) {
        StringBuffer output = new StringBuffer();
        int lastIndex = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '.' :
                    output.append(input.charAt(lastIndex));
                    output.append('.');
                    lastIndex = i + 1;
                    break;
                case ' ' :
                case ',' :
                    if (lastIndex < i) {
                        output.append(input.substring(lastIndex, i));
                    }
                    output.append(c);
                    lastIndex = i + 1;
                    break;
            }
        }
        if (lastIndex < input.length()) {
            output.append(input.substring(lastIndex));
        }
        return output.toString();
    }

    protected void wtf() {
        List<ComponentDeclaration> downComponents = getDependenciesThatAreDown();
        if (downComponents.isEmpty()) {
            System.out.println("No missing dependencies found.");
        }
        else {
            System.out.println(downComponents.size() + " missing dependencies found.");
            System.out.println("-------------------------------------");
        }
        listResolvedBundles();
        listInstalledBundles();
        Set<ComponentId> downComponentsRoot = getTheRootCouses(downComponents);
        listAllMissingConfigurations(downComponentsRoot);
        listAllMissingServices(downComponents, downComponentsRoot);
    }

    private Set<ComponentId> getTheRootCouses(List<ComponentDeclaration> downComponents) {
        Set<ComponentId> downComponentsRoot = new TreeSet<ComponentId>();
        for (ComponentDeclaration c : downComponents) {
            List<ComponentId> root = getRoot(downComponents, c, new ArrayList<ComponentId>());
            downComponentsRoot.addAll(root);
        }
        return downComponentsRoot;
    }

    @SuppressWarnings("unchecked")
    private List<ComponentDeclaration> getDependenciesThatAreDown() {
        List<DependencyManager> dependencyManagers = DependencyManager.getDependencyManagers();
        List<ComponentDeclaration> downComponents = new ArrayList<ComponentDeclaration>();
        for (DependencyManager dm : dependencyManagers) {
            List<ComponentDeclaration> components = dm.getComponents();
            // first create a list of all down components
            for (ComponentDeclaration c : components) {
                if (c.getState() == ComponentDeclaration.STATE_UNREGISTERED) {
                    downComponents.add(c);
                }
            }
        }
        return downComponents;
    }

    private void listResolvedBundles() {
        boolean areResolved = false;
        for (Bundle b : m_context.getBundles()) {
            if (b.getState() == Bundle.RESOLVED && !isFragment(b)) {
                areResolved = true;
                break;
            }
        }
        if (areResolved) {
            System.out.println("Please note that the following bundles are in the RESOLVED state:");
            for (Bundle b : m_context.getBundles()) {
                if (b.getState() == Bundle.RESOLVED && !isFragment(b)) {
                    System.out.println(" * [" + b.getBundleId() + "] " + b.getSymbolicName());
                }
            }
        }
    }
    
    private void listInstalledBundles() {
        boolean areResolved = false;
        for (Bundle b : m_context.getBundles()) {
            if (b.getState() == Bundle.INSTALLED) {
                areResolved = true;
                break;
            }
        }
        if (areResolved) {
            System.out.println("Please note that the following bundles are in the INSTALLED state:");
            for (Bundle b : m_context.getBundles()) {
                if (b.getState() == Bundle.INSTALLED) {
                    System.out.println(" * [" + b.getBundleId() + "] " + b.getSymbolicName());
                }
            }
        }
    }

    private boolean isFragment(Bundle b) {
        @SuppressWarnings("unchecked")
        Dictionary<String, String> headers = b.getHeaders();
        return headers.get("Fragment-Host") != null;
    }

    private void listAllMissingConfigurations(Set<ComponentId> downComponentsRoot) {
        if (hasMissingType(downComponentsRoot, CONFIGURATION)) {
            System.out.println("The following configuration(s) are missing: ");
            for (ComponentId s : downComponentsRoot) {
                if (CONFIGURATION.equals(s.getType())) {
                    System.out.println(" * " + s.getName() + " for bundle " + s.getBundleName());
                }
            }
        }
    }

    private void listAllMissingServices(List<ComponentDeclaration> downComponents, Set<ComponentId> downComponentsRoot) {
        if (hasMissingType(downComponentsRoot, SERVICE)) {
            System.out.println("The following service(s) are missing: ");
            for (ComponentId s : downComponentsRoot) {
                if (SERVICE.equals(s.getType())) {
                    System.out.print(" * " + s.getName());
                    ComponentDeclaration component = getComponentDeclaration(s.getName(), downComponents);
                    if (component == null) {
                        System.out.println(" is not found in the service registry");
                    } else {
                        ComponentDependencyDeclaration[] componentDependencies = component.getComponentDependencies();
                        System.out.println(" and needs:");
                        for (ComponentDependencyDeclaration cdd : componentDependencies) {
                            if (cdd.getState() == ComponentDependencyDeclaration.STATE_UNAVAILABLE_REQUIRED) {
                                System.out.println(cdd.getName());
                            }
                        }
                        System.out.println(" to work");
                    }
                }
            }
        }
    }

    private boolean hasMissingType(Set<ComponentId> downComponentsRoot, String type) {
        for (ComponentId s : downComponentsRoot) {
            if (type.equals(s.getType())) {
                return true;
            }
        }
        return false;
    }
    
    private List<ComponentId> getRoot(List<ComponentDeclaration> downComponents, ComponentDeclaration c, List<ComponentId> backTrace) {
        ComponentDependencyDeclaration[] componentDependencies = c.getComponentDependencies();
        int downDeps = 0;
        List<ComponentId> result = new ArrayList<ComponentId>();
        for (ComponentDependencyDeclaration cdd : componentDependencies) {
            if (cdd.getState() == ComponentDependencyDeclaration.STATE_UNAVAILABLE_REQUIRED) {
                downDeps++;
                // Detect missing configuration dependency
                if (CONFIGURATION.equals(cdd.getType())) {
                    String bsn = c.getBundleContext().getBundle().getSymbolicName();
                    result.add(new ComponentId(cdd.getName(), cdd.getType(), bsn));
                    continue;
                }

                // Detect if the missing dependency is a root cause failure
                ComponentDeclaration component = getComponentDeclaration(cdd.getName(), downComponents);
                if (component == null) {
                    result.add(new ComponentId(cdd.getName(), cdd.getType(), null));
                    continue;
                }
                // Detect circular dependency
                ComponentId componentId = new ComponentId(cdd.getName(), cdd.getType(), null);
                if (backTrace.contains(componentId)) {
                    // We already got this one so its a circular dependency
                    System.out.print("Circular dependency found:\n *");
                    for (ComponentId cid : backTrace) {
                        System.out.print(" -> " + cid.getName() + " ");
                    }
                    System.out.println(" -> " + componentId.getName());
                    result.add(new ComponentId(c.getName(), SERVICE, c.getBundleContext().getBundle().getSymbolicName()));
                    continue;
                }
                backTrace.add(componentId);
                return getRoot(downComponents, component, backTrace);
            }
        }
        if (downDeps > 0 && result.isEmpty()) {
            result.add(new ComponentId(c.getName(), SERVICE, c.getBundleContext().getBundle().getSymbolicName()));
        }
        return result;
    }
    
    private ComponentDeclaration getComponentDeclaration(final String fullName, List<ComponentDeclaration> list) {
        String simpleName = getSimpleName(fullName);
        Properties props = parseProperties(fullName);
        for (ComponentDeclaration c : list) {
            String serviceNames = c.getName();
            int cuttOff = serviceNames.indexOf("(");
            if (cuttOff != -1) {
                serviceNames = serviceNames.substring(0, cuttOff).trim();
            }
            for (String serviceName : serviceNames.split(",")) {
                if (simpleName.equals(serviceName.trim()) && doPropertiesMatch(props, parseProperties(c.getName()))) {
                    return c;
                }
            }
        }
        return null;
    }
    
    private boolean doPropertiesMatch(Properties need, Properties provide) {
        for (Entry<Object, Object> entry : need.entrySet()) {
            Object prop = provide.get(entry.getKey());
            if (prop == null || !prop.equals(entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private String getSimpleName(String name) {
        int cuttOff = name.indexOf("(");
        if (cuttOff != -1) {
            return name.substring(0, cuttOff).trim();
        }
        return name.trim();
    }
    
    private Properties parseProperties(String name) {
        Properties result = new Properties();
        int cuttOff = name.indexOf("(");
        if (cuttOff != -1) {
            String propsText = name.substring(cuttOff + 1, name.indexOf(")"));
            String[] split = propsText.split(",");
            for (String prop : split) {
                String[] kv = prop.split("=");
                if (kv.length == 2) {
                    result.put(kv[0], kv[1]);
                }
            }
        }
        return result;
    }
    
    public static class DependencyManagerSorter implements Comparator<DependencyManager> {
        public int compare(DependencyManager dm1, DependencyManager dm2) {
            long id1 = dm1.getBundleContext().getBundle().getBundleId();
            long id2 = dm2.getBundleContext().getBundle().getBundleId();
            return id1 > id2 ? 1 : -1;
        }
    }
}
