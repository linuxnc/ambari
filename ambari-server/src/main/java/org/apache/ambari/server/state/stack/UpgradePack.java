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
package org.apache.ambari.server.state.stack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlElementWrapper;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;
import javax.xml.bind.annotation.XmlValue;

import org.apache.ambari.annotations.Experimental;
import org.apache.ambari.annotations.ExperimentalFeature;
import org.apache.ambari.server.state.stack.upgrade.AddComponentTask;
import org.apache.ambari.server.state.stack.upgrade.ClusterGrouping;
import org.apache.ambari.server.state.stack.upgrade.ClusterGrouping.ExecuteStage;
import org.apache.ambari.server.state.stack.upgrade.ConfigureTask;
import org.apache.ambari.server.state.stack.upgrade.CreateAndConfigureTask;
import org.apache.ambari.server.state.stack.upgrade.Direction;
import org.apache.ambari.server.state.stack.upgrade.Grouping;
import org.apache.ambari.server.state.stack.upgrade.Lifecycle;
import org.apache.ambari.server.state.stack.upgrade.LifecycleType;
import org.apache.ambari.server.state.stack.upgrade.ServiceCheckGrouping;
import org.apache.ambari.server.state.stack.upgrade.Task;
import org.apache.ambari.server.state.stack.upgrade.Task.Type;
import org.apache.ambari.server.state.stack.upgrade.UpgradeType;
import org.apache.commons.collections.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents an upgrade pack.
 */
@XmlRootElement(name="upgrade")
@XmlAccessorType(XmlAccessType.FIELD)
public class UpgradePack {

  private static final Logger LOG = LoggerFactory.getLogger(UpgradePack.class);

  /**
   * Name of the file without the extension, such as upgrade-2.2
   */
  private String name;

  @XmlElement(name="target")
  private String target;

  @XmlElement(name="target-stack")
  private String targetStack;

  @XmlElement(name="lifecycle")
  public List<Lifecycle> lifecycles;

  @XmlElementWrapper(name="order")
  @XmlElement(name="group")
  private List<Grouping> groups = new ArrayList<>();

  @XmlElementWrapper(name="prerequisite-checks")
  @XmlElement(name="check")
  private List<PrerequisiteCheckDefinition> prerequisiteChecks;

  /**
   * In the case of a rolling upgrade, will specify processing logic for a particular component.
   * NonRolling upgrades are simpler so the "processing" is embedded into the  group's "type", which is a function like
   * "stop" or "start".
   */
  @XmlElementWrapper(name="processing")
  @XmlElement(name="service")
  private List<ProcessingService> processing;

  /**
   * {@code true} to automatically skip slave/client component failures. The
   * default is {@code false}.
   */
  @XmlElement(name = "skip-failures")
  private boolean skipFailures = false;

  /**
   * {@code true} to allow downgrade, {@code false} to disable downgrade.
   * Tag is optional and can be {@code null}, use {@code isDowngradeAllowed} getter instead.
   */
  @XmlElement(name = "downgrade-allowed", required = false, defaultValue = "true")
  private boolean downgradeAllowed = true;


  /**
   * Initialized once by {@link #afterUnmarshal(Unmarshaller, Object)}.
   */
  @XmlTransient
  private Map<String, Map<String, ProcessingComponent>> m_process = null;

  /**
   * A mapping of SERVICE/COMPONENT to any {@link AddComponentTask} instances.
   */
  @XmlTransient
  private final Map<String, AddComponentTask> m_addComponentTasks = new LinkedHashMap<>();

  @XmlTransient
  private boolean m_resolvedGroups = false;

  @XmlElement(name="type", defaultValue="rolling")
  private UpgradeType type;

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }
  /**
   * @return the target version for the upgrade pack
   */
  public String getTarget() {
    return target;
  }

  /**
   * @return the type of upgrade, e.g., "ROLLING" or "NON_ROLLING"
   */
  public UpgradeType getType() {
    return type;
  }

  /**
   * @return the preCheck name, e.g. "CheckDescription"
   */
  public List<String> getPrerequisiteChecks() {
    if (prerequisiteChecks == null) {
      return new ArrayList<>();
    }

    return prerequisiteChecks.stream().map(c -> c.className).collect(Collectors.toList());
  }

  /**
   * Merges the processing section of the upgrade xml with
   * the processing section from a service's upgrade xml.
   * These are added to the end of the current list of services.
   *
   * @param pack
   *              the service's upgrade pack
   */
  public void mergeProcessing(UpgradePack pack) {
    List<ProcessingService> list = pack.processing;
    if (list == null) {
      return;
    }
    if (processing == null) {
      processing = list;
      return;
    }
    processing.addAll(list);

    // new processing has been created, so rebuild the mappings
    initializeProcessingComponentMappings();
  }

  /**
   * @return the target stack, or {@code null} if the upgrade is within the same stack
   */
  public String getTargetStack() {
    return targetStack;
  }

  /**
   * Used to get all groups defined for an upgrade.  This method is deprecated as orchestration
   * will change to per-lifecycle.  At the time of writing, keep this for compilation purposes.
   * @return
   */
  @Experimental(feature = ExperimentalFeature.MPACK_UPGRADES)
  @Deprecated
  public List<Grouping> getAllGroups() {

    return Collections.emptyList();
  }

  /**
   * Gets the groups defined for the upgrade pack. If a direction is defined for
   * a group, it must match the supplied direction to be returned
   *
   * @param direction
   *          the direction to return the ordered groups
   * @return the list of groups
   */
  public List<Grouping> getGroups(LifecycleType type, Direction direction) {

    // !!! lifecycles are bound to be only one per-type per-Upgrade Pack, so findFirst() is ok here
    Optional<Lifecycle> optional = lifecycles.stream().filter(l -> l.type == type).findFirst();
    if (!optional.isPresent()) {
      return Collections.<Grouping>emptyList();
    }

    List<Grouping> list;
    List<Grouping> groups = optional.get().groups;

    if (direction.isUpgrade()) {
      list = groups;
    } else {
      switch (this.type) {
        case EXPRESS:
          list = getDowngradeGroupsForNonrolling(groups);
          break;
        case HOST_ORDERED:
        case ROLLING:
        default:
          list = getDowngradeGroupsForRolling(groups);
          break;
      }
    }

    List<Grouping> checked = new ArrayList<>();
    for (Grouping group : list) {
      if (null == group.intendedDirection || direction == group.intendedDirection) {
        checked.add(group);
      }

    }

    return checked;
  }

  /**
   * @return {@code true} if upgrade pack supports downgrade or {@code false} if not.
   */
  public boolean isDowngradeAllowed(){
    return downgradeAllowed;
  }

  public boolean canBeApplied(String targetVersion){
    // check that upgrade pack can be applied to selected stack
    // converting 2.2.*.* -> 2\.2(\.\d+)?(\.\d+)?(-\d+)?
    String regexPattern = getTarget().replaceAll("\\.", "\\\\."); // . -> \.
    regexPattern = regexPattern.replaceAll("\\\\\\.\\*", "(\\\\\\.\\\\d+)?"); // \.* -> (\.\d+)?
    regexPattern = regexPattern.concat("(-\\d+)?");
    return Pattern.matches(regexPattern, targetVersion);
  }

  /**
   * Calculates the group orders when performing a rolling downgrade
   * <ul>
   *   <li>ClusterGroupings must remain at the same positions (first/last).</li>
   *   <li>When there is a ServiceCheck group, it must ALWAYS follow the same</li>
   *       preceding group, whether for an upgrade or a downgrade.</li>
   *   <li>All other groups must follow the reverse order.</li>
   * </ul>
   * For example, give the following order of groups:
   * <ol>
   *   <li>PRE_CLUSTER</li>
   *   <li>ZK</li>
   *   <li>CORE_MASTER</li>
   *   <li>SERVICE_CHECK_1</li>
   *   <li>CLIENTS</li>
   *   <li>FLUME</li>
   *   <li>SERVICE_CHECK_2</li>
   *   <li>POST_CLUSTER</li>
   * </ol>
   * The reverse would be:
   * <ol>
   *   <li>PRE_CLUSTER</li>
   *   <li>FLUME</li>
   *   <li>SERVICE_CHECK_2</li>
   *   <li>CLIENTS</li>
   *   <li>CORE_MASTER</li>
   *   <li>SERVICE_CHECK_1</li>
   *   <li>ZK</li>
   *   <li>POST_CLUSTER</li>
   * </ol>
   * @return the list of groups, reversed appropriately for a downgrade.
   */
  private List<Grouping> getDowngradeGroupsForRolling(List<Grouping> groups) {
    List<Grouping> reverse = new ArrayList<>();

    // !!! Testing exposed groups.size() == 1 issue.  Normally there's no precedent for
    // a one-group upgrade pack, so take it into account anyway.
    if (groups.size() == 1) {
      return groups;
    }

    int idx = 0;
    int iter = 0;
    Iterator<Grouping> it = groups.iterator();

    while (it.hasNext()) {
      Grouping g = it.next();
      if (ClusterGrouping.class.isInstance(g)) {
        reverse.add(g);
        idx++;
      } else {
        if (iter+1 < groups.size()) {
          Grouping peek = groups.get(iter+1);
          if (ServiceCheckGrouping.class.isInstance(peek)) {
            reverse.add(idx, it.next());
            reverse.add(idx, g);
            iter++;
          } else {
            reverse.add(idx, g);
          }
        }
      }
      iter++;
    }

    return reverse;
  }

  private List<Grouping> getDowngradeGroupsForNonrolling(List<Grouping> groups) {
    List<Grouping> list = new ArrayList<>();
    for (Grouping g : groups) {
      list.add(g);
    }
    return list;
  }

  /**
   * Gets the tasks by which services and components should be upgraded.
   * @return a map of service_name -> map(component_name -> process).
   */
  public Map<String, Map<String, ProcessingComponent>> getTasks() {
    return m_process;
  }

  /**
   * Gets a mapping of SERVICE/COMPONENT to {@link AddComponentTask} for this
   * upgrade pack.
   *
   * @return
   */
  public Map<String, AddComponentTask> getAddComponentTasks() {
    return m_addComponentTasks;
  }

  /**
   * This method is called after all the properties (except IDREF) are
   * unmarshalled for this object, but before this object is set to the parent
   * object. This is done automatically by the {@link Unmarshaller}.
   * <p/>
   * Currently, this method performs the following post-unmarshal operations:
   * <ul>
   * <li>Builds a mapping of service name to a mapping of component name to
   * {@link ProcessingComponent}</li>
   * </ul>
   *
   * @param unmarshaller
   *          the unmarshaller used to unmarshal this instance
   * @param parent
   *          the parent object, if any, that this will be set on.
   * @see Unmarshaller
   */
  void afterUnmarshal(Unmarshaller unmarshaller, Object parent) {
    initializeProcessingComponentMappings();
    initializeAddComponentTasks();
  }

  /**
   * Builds a mapping of component name to {@link ProcessingComponent} and then
   * maps those to service name, initializing {@link #m_process} to the result.
   */
  private void initializeProcessingComponentMappings() {
    m_process = new LinkedHashMap<>();

    if (CollectionUtils.isEmpty(processing)) {
      return;
    }

    for (ProcessingService svc : processing) {
      Map<String, ProcessingComponent> componentMap = m_process.get(svc.name);

      // initialize mapping if not present for the given service name
      if (null == componentMap) {
        componentMap = new LinkedHashMap<>();
        m_process.put(svc.name, componentMap);
      }

      for (ProcessingComponent pc : svc.components) {
        if (pc != null) {
          componentMap.put(pc.name, pc);
        } else {
          LOG.warn("ProcessingService {} has null amongst it's values (total {} components)",
              svc.name, svc.components.size());
        }
      }
    }
  }

  /**
   * Builds a mapping of SERVICE/COMPONENT to {@link AddComponentTask}.
   */
  private void initializeAddComponentTasks() {
    for (Grouping group : groups) {
      if (ClusterGrouping.class.isInstance(group)) {
        List<ExecuteStage> executeStages = ((ClusterGrouping) group).executionStages;
        for (ExecuteStage executeStage : executeStages) {
          Task task = executeStage.task;

          // keep track of this for later ...
          if (task.getType() == Type.ADD_COMPONENT) {
            AddComponentTask addComponentTask = (AddComponentTask) task;
            m_addComponentTasks.put(addComponentTask.getServiceAndComponentAsString(),
                addComponentTask);
          }
        }
      }
    }
  }

  /**
   * @return {@code true} if the upgrade targets any version or stack.  Both
   * {@link #target} and {@link #targetStack} must equal "*"
   */
  @Deprecated
  @Experimental(feature=ExperimentalFeature.MPACK_UPGRADES)
  public boolean isAllTarget() {
    return false;
  }


  /**
   * A service definition that holds a list of components in the 'order' element.
   */
  public static class OrderService {

    @XmlAttribute(name="name")
    public String serviceName;

    @XmlElement(name="component")
    public List<String> components;
  }

  /**
   * A service definition in the 'processing' element.
   */
  public static class ProcessingService {

    @XmlAttribute
    public String name;

    @XmlElement(name="component")
    public List<ProcessingComponent> components;
  }

  /**
   * A component definition in the 'processing/service' path.
   */
  public static class ProcessingComponent {

    @XmlAttribute
    public String name;

    @XmlElementWrapper(name="pre-upgrade")
    @XmlElement(name="task")
    public List<Task> preTasks;

    @XmlElement(name="pre-downgrade")
    private DowngradeTasks preDowngradeXml;
    @XmlTransient
    public List<Task> preDowngradeTasks;

    @XmlElementWrapper(name="upgrade")
    @XmlElement(name="task")
    public List<Task> tasks;

    @XmlElementWrapper(name="post-upgrade")
    @XmlElement(name="task")
    public List<Task> postTasks;


    @XmlElement(name="post-downgrade")
    private DowngradeTasks postDowngradeXml;
    @XmlTransient
    public List<Task> postDowngradeTasks;

    /**
     * This method verifies that if {@code pre-upgrade} is defined, that there is also
     * a sibling {@code pre-downgrade} defined.  Similarly, {@code post-upgrade} must have a
     * sibling {@code post-downgrade}.  This is because the JDK (include 1.8) JAXB doesn't
     * support XSD xpath assertions for siblings.
     *
     * @param unmarshaller  the unmarshaller
     * @param parent        the parent
     */
    void afterUnmarshal(Unmarshaller unmarshaller, Object parent) {
      if (null != preDowngradeXml) {
        preDowngradeTasks = preDowngradeXml.copyUpgrade ? preTasks :
          preDowngradeXml.tasks;
      }

      if (null != postDowngradeXml) {
        postDowngradeTasks = postDowngradeXml.copyUpgrade ? postTasks :
          postDowngradeXml.tasks;
      }

      ProcessingService service = (ProcessingService) parent;

      if (LOG.isDebugEnabled()) {
        LOG.debug("Processing component {}/{} preUpgrade={} postUpgrade={} preDowngrade={} postDowngrade={}",
            preTasks, preDowngradeTasks, postTasks, postDowngradeTasks);
      }

      if (null != preTasks && null == preDowngradeTasks) {
        String error = String.format("Upgrade pack must contain pre-downgrade elements if "
            + "pre-upgrade exists for processing component %s/%s", service.name, name);

        throw new RuntimeException(error);
      }

      if (null != postTasks && null == postDowngradeTasks) {
        String error = String.format("Upgrade pack must contain post-downgrade elements if "
            + "post-upgrade exists for processing component %s/%s", service.name, name);

        throw new RuntimeException(error);
      }

      // !!! check for config tasks and mark the associated service
      initializeTasks(service.name, preTasks);
      initializeTasks(service.name, postTasks);
      initializeTasks(service.name, tasks);
      initializeTasks(service.name, preDowngradeTasks);
      initializeTasks(service.name, postDowngradeTasks);
    }

    /**
     * Checks for config tasks and marks the associated service.
     * @param service
     *          the service name
     * @param tasks
     *          the list of tasks to check
     */
    private void initializeTasks(String service, List<Task> tasks) {
      if (CollectionUtils.isEmpty(tasks)) {
        return;
      }

      for (Task task : tasks) {
        if (Task.Type.CONFIGURE == task.getType()) {
          ((ConfigureTask) task).associatedService = service;
        } else if (Task.Type.CREATE_AND_CONFIGURE == task.getType()) {
          ((CreateAndConfigureTask) task).associatedService = service;
        }
      }
    }
  }

  /**
   * @return
   */
  public PrerequisiteCheckConfig getPrerequisiteCheckConfig() {
    return new PrerequisiteCheckConfig(prerequisiteChecks);
  }

  /**
   * Holds the config properties for all the checks defined for an Upgrade Pack.
   */
  public static class PrerequisiteCheckConfig {
    private List<PrerequisiteCheckDefinition> m_checks;
    private PrerequisiteCheckConfig(List<PrerequisiteCheckDefinition> checks) {
      m_checks = checks;
    }

    public Map<String, String> getCheckProperties(String checkName) {
      if (null == m_checks) {
        return Collections.emptyMap();
      }

      Optional<PrerequisiteCheckDefinition> optional = m_checks.stream()
          .filter(c -> c.className.equals(checkName)).findFirst();

      if (!optional.isPresent()) {
        return Collections.emptyMap();
      }

      PrerequisiteCheckDefinition checks = optional.get();

      Map<String, String> map = checks.properties.stream().collect(Collectors.toMap(
          c -> c.name, c -> c.value));

      return map;
    }
  }

  /**
   * The definition for each check in the Upgrade Pack.
   */
  public static class PrerequisiteCheckDefinition {
    @XmlAttribute(name="class")
    public String className;

    @XmlElement(name="property")
    public List<PrerequisiteProperty> properties = new ArrayList<>();
  }

  /**
   * Prerequisite check config property
   */
  public static class PrerequisiteProperty {
    @XmlAttribute
    public String name;

    @XmlValue
    public String value;
  }

  /**
   * A {@code (pre|post)-downgrade} can have an attribute as well as contain {@code task} elements.
   */
  private static class DowngradeTasks {

    @XmlAttribute(name="copy-upgrade")
    private boolean copyUpgrade = false;

    @XmlElement(name="task")
    private List<Task> tasks = new ArrayList<>();
  }

  /**
   * @return true if this upgrade pack contains a group with a task that matches the given predicate
   */
  public boolean anyGroupTaskMatch(Predicate<Task> taskPredicate) {
    return getAllGroups().stream()
      .filter(ClusterGrouping.class::isInstance)
      .flatMap(group -> ((ClusterGrouping) group).executionStages.stream())
      .map(executeStage -> executeStage.task)
      .anyMatch(taskPredicate);
  }
}
