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
package org.jclouds.vsphere.compute.config;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.base.Throwables;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.Closer;
import com.vmware.vim25.CustomFieldDef;
import com.vmware.vim25.Description;
import com.vmware.vim25.FileTransferInformation;
import com.vmware.vim25.GuestNicInfo;
import com.vmware.vim25.GuestProcessInfo;
import com.vmware.vim25.GuestProgramSpec;
import com.vmware.vim25.NamePasswordAuthentication;
import com.vmware.vim25.TaskInProgress;
import com.vmware.vim25.VirtualCdrom;
import com.vmware.vim25.VirtualCdromIsoBackingInfo;
import com.vmware.vim25.VirtualDevice;
import com.vmware.vim25.VirtualDeviceConfigSpec;
import com.vmware.vim25.VirtualDeviceConfigSpecFileOperation;
import com.vmware.vim25.VirtualDeviceConfigSpecOperation;
import com.vmware.vim25.VirtualDeviceConnectInfo;
import com.vmware.vim25.VirtualDisk;
import com.vmware.vim25.VirtualDiskFlatVer2BackingInfo;
import com.vmware.vim25.VirtualEthernetCard;
import com.vmware.vim25.VirtualEthernetCardNetworkBackingInfo;
import com.vmware.vim25.VirtualFloppy;
import com.vmware.vim25.VirtualFloppyImageBackingInfo;
import com.vmware.vim25.VirtualLsiLogicController;
import com.vmware.vim25.VirtualMachineCloneSpec;
import com.vmware.vim25.VirtualMachineConfigSpec;
import com.vmware.vim25.VirtualMachinePowerState;
import com.vmware.vim25.VirtualVmxnet3;
import com.vmware.vim25.mo.Datastore;
import com.vmware.vim25.mo.Folder;
import com.vmware.vim25.mo.GuestAuthManager;
import com.vmware.vim25.mo.GuestFileManager;
import com.vmware.vim25.mo.GuestOperationsManager;
import com.vmware.vim25.mo.GuestProcessManager;
import com.vmware.vim25.mo.HostSystem;
import com.vmware.vim25.mo.InventoryNavigator;
import com.vmware.vim25.mo.ManagedEntity;
import com.vmware.vim25.mo.ResourcePool;
import com.vmware.vim25.mo.Task;
import com.vmware.vim25.mo.VirtualMachine;
import org.jclouds.compute.ComputeServiceAdapter;
import org.jclouds.compute.domain.Hardware;
import org.jclouds.compute.domain.HardwareBuilder;
import org.jclouds.compute.domain.Image;
import org.jclouds.compute.domain.Processor;
import org.jclouds.compute.domain.Template;
import org.jclouds.compute.domain.Volume;
import org.jclouds.compute.domain.VolumeBuilder;
import org.jclouds.compute.reference.ComputeServiceConstants;
import org.jclouds.domain.Location;
import org.jclouds.domain.LoginCredentials;
import org.jclouds.logging.Logger;
import org.jclouds.vsphere.VSphereApiMetadata;
import org.jclouds.vsphere.compute.options.VSphereTemplateOptions;
import org.jclouds.vsphere.compute.strategy.NetworkConfigurationForNetworkAndOptions;
import org.jclouds.vsphere.config.VSphereConstants;
import org.jclouds.vsphere.domain.InstanceType;
import org.jclouds.vsphere.domain.VSphereHost;
import org.jclouds.vsphere.domain.VSphereServiceInstance;
import org.jclouds.vsphere.domain.network.NetworkConfig;
import org.jclouds.vsphere.functions.MasterToVirtualMachineCloneSpec;
import org.jclouds.vsphere.functions.VirtualMachineToImage;
import org.jclouds.vsphere.predicates.VSpherePredicate;
import org.jclouds.vsphere.util.ComputerNameValidator;

import javax.annotation.Resource;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.io.IOException;
import java.rmi.RemoteException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Throwables.propagate;
import static org.jclouds.vsphere.config.VSphereConstants.CLONING;

/**
 * based on Andrea Turli work.
 */
@Singleton
public class VSphereComputeServiceAdapter implements
        ComputeServiceAdapter<VirtualMachine, Hardware, Image, Location> {

    private final ReentrantLock lock = new ReentrantLock();

   @Resource
   @Named(ComputeServiceConstants.COMPUTE_LOGGER)
   protected Logger logger = Logger.NULL;

   protected String vmInitPassword = null;

   private Supplier<VSphereServiceInstance> serviceInstance;
   private Supplier<Map<String, CustomFieldDef>> customFields;

   private final VirtualMachineToImage virtualMachineToImage;
   protected final NetworkConfigurationForNetworkAndOptions networkConfigurationForNetworkAndOptions;
   private final Supplier<VSphereHost> vSphereHost;

   @Inject
   public VSphereComputeServiceAdapter(Supplier<VSphereServiceInstance> serviceInstance, Supplier<Map<String, CustomFieldDef>> customFields, Supplier<VSphereHost> vSphereHost,
                                       VirtualMachineToImage virtualMachineToImage,
                                       NetworkConfigurationForNetworkAndOptions networkConfigurationForNetworkAndOptions,
                                       @Named(VSphereConstants.JCLOUDS_VSPHERE_VM_PASSWORD) String vmInitPassword) {
      this.serviceInstance = checkNotNull(serviceInstance, "serviceInstance");
      this.customFields = checkNotNull(customFields, "customFields");
      this.virtualMachineToImage = virtualMachineToImage;
      this.vmInitPassword = checkNotNull(vmInitPassword, "vmInitPassword");
      this.networkConfigurationForNetworkAndOptions = checkNotNull(networkConfigurationForNetworkAndOptions, "networkConfigurationForNetworkAndOptions");
      this.vSphereHost = checkNotNull(vSphereHost, "vSphereHost");
   }

   @Override
   public NodeAndInitialCredentials<VirtualMachine> createNodeWithGroupEncodedIntoName(String tag, String name, Template template) {
      try {
         Closer closer = Closer.create();
         VSphereServiceInstance instance = null;
         try {
            instance = this.serviceInstance.get();
            VSphereHost sphereHost = vSphereHost.get();
            closer.register(instance);
            closer.register(sphereHost);
            Folder rootFolder = instance.getInstance().getRootFolder();

            ComputerNameValidator.INSTANCE.validate(name);

            VirtualMachine master = getVMwareTemplate(template.getImage().getId(), rootFolder);
            ResourcePool resourcePool = checkNotNull(tryFindResourcePool(rootFolder, sphereHost.getHost().getName()), "resourcePool");

            VirtualMachineCloneSpec cloneSpec = new MasterToVirtualMachineCloneSpec(resourcePool, sphereHost.getDatastore(),
                    VSphereApiMetadata.defaultProperties().getProperty(CLONING)).apply(master);

            VSphereTemplateOptions vOptions = VSphereTemplateOptions.class.cast(template.getOptions());
            Set<String> networks = vOptions.getNetworks();

            VirtualMachineConfigSpec virtualMachineConfigSpec = new VirtualMachineConfigSpec();
            virtualMachineConfigSpec.setMemoryMB((long) template.getHardware().getRam());
             if (template.getHardware().getProcessors().size() > 0)
                virtualMachineConfigSpec.setNumCPUs((int)template.getHardware().getProcessors().get(0).getCores());
             else
                virtualMachineConfigSpec.setNumCPUs(1);



            Set<NetworkConfig> networkConfigs = Sets.newHashSet();
            for (String network : networks) {
               NetworkConfig config = networkConfigurationForNetworkAndOptions.apply(network, vOptions);
               networkConfigs.add(config);
            }

            List<VirtualDeviceConfigSpec> updates = Lists.newArrayList();

            long currentDiskSize = 0;
            int numberOfHardDrives = 0;

            int diskKey = 0;

            for (VirtualDevice device : master.getConfig().getHardware().getDevice()) {
               if (device instanceof VirtualDisk) {
                  VirtualDisk vd = (VirtualDisk) device;
                  diskKey = vd.getKey();
                  currentDiskSize += vd.getCapacityInKB();
                  numberOfHardDrives++;
               }
            }


            for (VirtualDevice device : master.getConfig().getHardware().getDevice()) {
               if (device instanceof VirtualEthernetCard) {
                  VirtualDeviceConfigSpec nicSpec = new VirtualDeviceConfigSpec();
                  nicSpec.setOperation(VirtualDeviceConfigSpecOperation.remove);
                  nicSpec.setDevice(device);
                  updates.add(nicSpec);
               } else if (device instanceof VirtualCdrom) {
                  if (vOptions.isoFileName() != null) {
                     VirtualCdrom vCdrom = (VirtualCdrom) device;
                     VirtualDeviceConfigSpec cdSpec = new VirtualDeviceConfigSpec();
                     cdSpec.setOperation(VirtualDeviceConfigSpecOperation.edit);

                     VirtualCdromIsoBackingInfo iso = new VirtualCdromIsoBackingInfo();
                     Datastore datastore = vSphereHost.get().getDatastore();
                     VirtualDeviceConnectInfo cInfo = new VirtualDeviceConnectInfo();
                     cInfo.setStartConnected(true);
                     cInfo.setConnected(true);
                     iso.setDatastore(datastore.getMOR());
                     iso.setFileName("[" + datastore.getName() + "] " + vOptions.isoFileName());

                     vCdrom.setConnectable(cInfo);
                     vCdrom.setBacking(iso);
                     cdSpec.setDevice(vCdrom);
                     updates.add(cdSpec);
                  }
               } else if (device instanceof VirtualFloppy) {
                  if (vOptions.flpFileName() != null) {
                     VirtualFloppy vFloppy = (VirtualFloppy) device;
                     VirtualDeviceConfigSpec floppySpec = new VirtualDeviceConfigSpec();
                     floppySpec.setOperation(VirtualDeviceConfigSpecOperation.edit);

                     VirtualFloppyImageBackingInfo image = new VirtualFloppyImageBackingInfo();
                     Datastore datastore = vSphereHost.get().getDatastore();
                     VirtualDeviceConnectInfo cInfo = new VirtualDeviceConnectInfo();
                     cInfo.setStartConnected(true);
                     cInfo.setConnected(true);
                     image.setDatastore(datastore.getMOR());
                     image.setFileName("[" + datastore.getName() + "] " + vOptions.flpFileName());

                     vFloppy.setConnectable(cInfo);
                     vFloppy.setBacking(image);
                     floppySpec.setDevice(vFloppy);
                     updates.add(floppySpec);
                  }
               } else if (device instanceof VirtualLsiLogicController) {
                  //int unitNumber = master.getConfig().getHardware().getDevice().length;
                  int unitNumber = numberOfHardDrives;
                  List<? extends Volume> volumes = template.getHardware().getVolumes();
                  VirtualLsiLogicController lsiLogicController = (VirtualLsiLogicController) device;
                  String dsName = vSphereHost.get().getDatastore().getName();
                  for (Volume volume : volumes) {
                     long currentVolumeSize = 1024 * 1024 * volume.getSize().longValue();

                     VirtualDeviceConfigSpec diskSpec = new VirtualDeviceConfigSpec();

                     VirtualDisk disk = new VirtualDisk();
                     VirtualDiskFlatVer2BackingInfo diskFileBacking = new VirtualDiskFlatVer2BackingInfo();


                     int ckey = lsiLogicController.getKey();
                     unitNumber++;

                     String fileName = "[" + dsName + "] " + name + "/" + name + unitNumber + ".vmdk";

                     diskFileBacking.setFileName(fileName);
                     diskFileBacking.setDiskMode("persistent");

                     disk.setControllerKey(ckey);
                     disk.setUnitNumber(unitNumber);
                     disk.setBacking(diskFileBacking);
                     long size = currentVolumeSize;
                     disk.setCapacityInKB(size);
                     disk.setKey(-1);

                     diskSpec.setOperation(VirtualDeviceConfigSpecOperation.add);
                     diskSpec.setFileOperation(VirtualDeviceConfigSpecFileOperation.create);
                     diskSpec.setDevice(disk);
                     updates.add(diskSpec);
                  }

               }
            }
            updates.addAll(createNicSpec(networkConfigs));
            virtualMachineConfigSpec.setDeviceChange(updates.toArray(new VirtualDeviceConfigSpec[updates.size()]));

//                VirtualMachineBootOptions bootOptions = new VirtualMachineBootOptions();
//                List<VirtualMachineBootOptionsBootableDevice> bootOrder = Lists.newArrayList();
//
//                VirtualMachineBootOptionsBootableDiskDevice diskBootDevice = new VirtualMachineBootOptionsBootableDiskDevice();
//                diskBootDevice.setDeviceKey(diskKey);
//                bootOrder.add(diskBootDevice);
//                bootOrder.add(new VirtualMachineBootOptionsBootableCdromDevice());
//                bootOptions.setBootOrder(bootOrder.toArray(new VirtualMachineBootOptionsBootableDevice[0]));
//
//                virtualMachineConfigSpec.setBootOptions(bootOptions);

            cloneSpec.setConfig(virtualMachineConfigSpec);

            vOptions.getPublicKey();

            VirtualMachine cloned = null;
            try {
               cloned = cloneMaster(master, tag, name, cloneSpec);
               Set<String> tagsFromOption = vOptions.getTags();
               if (tagsFromOption.size() > 0) {
                  StringBuilder tags = new StringBuilder();
                  for (String vmTag : vOptions.getTags()) {
                     tags.append(vmTag).append(",");
                  }
                  tags.deleteCharAt(tags.length() - 1);

                  cloned.getServerConnection().getServiceInstance().getCustomFieldsManager().setField(cloned, customFields.get().get(VSphereConstants.JCLOUDS_TAGS).getKey(), tags.toString());
                  cloned.getServerConnection().getServiceInstance().getCustomFieldsManager().setField(cloned, customFields.get().get(VSphereConstants.JCLOUDS_GROUP).getKey(), tag);
                  if (vOptions.postConfiguration())
                     postConfiguration(cloned, name, tag, networkConfigs);
                  else {
                      VSpherePredicate.WAIT_FOR_VMTOOLS(1000 * 60 * 60 * 2, TimeUnit.MILLISECONDS).apply(cloned);
                  }
               }
            } catch (Exception e) {
               logger.error("Can't clone vm " + master.getName(), e);
               propagate(e);
            }

            if (vOptions.waitOnPort() != null) {

            }


            NodeAndInitialCredentials<VirtualMachine> nodeAndInitialCredentials = new NodeAndInitialCredentials<VirtualMachine>(cloned, cloned.getName(),
                    LoginCredentials.builder().user("root")
                            .password(vmInitPassword)
                            .build());
            return nodeAndInitialCredentials;
         } catch (Throwable e) { // must catch Throwable
            throw closer.rethrow(e);
         } finally {
            closer.close();
         }
      } catch (Throwable t) {
         Throwables.propagateIfPossible(t);
      }
      return null;
   }

   private Iterable<VirtualMachine> listNodes(VSphereServiceInstance instance) {
      Iterable<VirtualMachine> vms = ImmutableSet.of();
      try {
         Folder nodesFolder = instance.getInstance().getRootFolder();
         ManagedEntity[] managedEntities = new InventoryNavigator(nodesFolder).searchManagedEntities("VirtualMachine");
         vms = Iterables.transform(Arrays.asList(managedEntities), new Function<ManagedEntity, VirtualMachine>() {
            public VirtualMachine apply(ManagedEntity input) {
               return (VirtualMachine) input;
            }
         });
      } catch (Throwable e) {
         logger.error("Can't find vm", e);
      }
      return vms;
   }

   @Override
   public Iterable<VirtualMachine> listNodes() {
      Closer closer = Closer.create();
      VSphereServiceInstance instance = serviceInstance.get();
      closer.register(instance);

      try {
         try {
            return listNodes(instance);
         } catch (Throwable e) {
            logger.error("Can't find vm", e);
            throw closer.rethrow(e);
         } finally {
            closer.close();
         }
      } catch (Throwable t) {
         return ImmutableSet.of();
      }
   }

   @Override
   public Iterable<VirtualMachine> listNodesByIds(Iterable<String> ids) {
      Iterable<VirtualMachine> nodes = listNodes();
      Iterable<VirtualMachine> selectedNodes = Iterables.filter(nodes, VSpherePredicate.isNodeIdInList(ids));
      return selectedNodes;
   }

   @Override
   public Iterable<Hardware> listHardwareProfiles() {
      Set<org.jclouds.compute.domain.Hardware> hardware = Sets.newLinkedHashSet();
      hardware.add(new HardwareBuilder().ids(InstanceType.C1_M1_D10).hypervisor("vSphere").name(InstanceType.C1_M1_D10)
              .processor(new Processor(1, 1.0))
              .ram(1024)
              .volume(new VolumeBuilder().size(20f).type(Volume.Type.LOCAL).build())
              .build());

      hardware.add(new HardwareBuilder().ids(InstanceType.C2_M2_D30).hypervisor("vSphere").name(InstanceType.C2_M2_D30)
              .processor(new Processor(2, 1.0))
              .ram(2048)
              .volume(new VolumeBuilder().size(30f).type(Volume.Type.LOCAL).build())
              .build());

      hardware.add(new HardwareBuilder().ids(InstanceType.C2_M2_D50).hypervisor("vSphere").name(InstanceType.C2_M2_D50)
              .processor(new Processor(2, 1.0))
              .ram(2048)
              .volume(new VolumeBuilder().size(50f).type(Volume.Type.LOCAL).build())
              .build());

      hardware.add(new HardwareBuilder().ids(InstanceType.C2_M4_D50).hypervisor("vSphere").name(InstanceType.C2_M4_D50)
              .processor(new Processor(2, 2.0))
              .ram(4096)
              .volume(new VolumeBuilder().size(50f).type(Volume.Type.LOCAL).build())
              .build());

      hardware.add(new HardwareBuilder().ids(InstanceType.C2_M10_D80).hypervisor("vSphere").name(InstanceType.C2_M10_D80)
              .processor(new Processor(2, 2.0))
              .ram(10240)
              .volume(new VolumeBuilder().size(80f).type(Volume.Type.LOCAL).build())
              .build());

      hardware.add(new HardwareBuilder().ids(InstanceType.C3_M10_D80).hypervisor("vSphere").name(InstanceType.C3_M10_D80)
              .processor(new Processor(3, 2.0))
              .ram(10240)
              .volume(new VolumeBuilder().size(80f).type(Volume.Type.LOCAL).build())
              .build());

      hardware.add(new HardwareBuilder().ids(InstanceType.C4_M4_D10).hypervisor("vSphere").name(InstanceType.C4_M4_D10)
              .processor(new Processor(4, 2.0))
              .ram(4096)
              .volume(new VolumeBuilder().size(10f).type(Volume.Type.LOCAL).build())
              .build());

      hardware.add(new HardwareBuilder().ids(InstanceType.C2_M6_D40).hypervisor("vSphere").name(InstanceType.C2_M6_D40)
              .processor(new Processor(2, 2.0))
              .ram(6 * 1024)
              .volume(new VolumeBuilder().size(40f).type(Volume.Type.LOCAL).build())
              .build());
      hardware.add(new HardwareBuilder().ids(InstanceType.C8_M16_D30).hypervisor("vSphere").name(InstanceType.C8_M16_D30)
              .processor(new Processor(8, 2.0))
              .ram(16 * 1024)
              .volume(new VolumeBuilder().size(30f).type(Volume.Type.LOCAL).build())
              .build());
      hardware.add(new HardwareBuilder().ids(InstanceType.C8_M16_D80).hypervisor("vSphere").name(InstanceType.C8_M16_D80)
              .processor(new Processor(8, 2.0))
              .ram(16 * 1024)
              .volume(new VolumeBuilder().size(80f).type(Volume.Type.LOCAL).build())
              .build());

      return hardware;
   }

   @Override
   public Iterable<Image> listImages() {
      Closer closer = Closer.create();
      VSphereServiceInstance instance = serviceInstance.get();
      closer.register(instance);
      try {
         try {
            Iterable<VirtualMachine> nodes = listNodes(instance);
            Iterable<VirtualMachine> templates = Iterables.filter(nodes, VSpherePredicate.isTemplatePredicate);
            Iterable<Image> images = Iterables.transform(templates, virtualMachineToImage);
            return FluentIterable.from(images).toList();

         } catch (Throwable t) {
            throw closer.rethrow(t);
         } finally {
            closer.close();
         }
      } catch (Throwable t) {
         return ImmutableSet.of();
      }
   }

   @Override
   public Iterable<Location> listLocations() {
      // Not using the adapter to determine locations
      return ImmutableSet.<Location>of();
   }

   @Override
   public VirtualMachine getNode(String vmName) {
      Closer closer = Closer.create();
      VSphereServiceInstance instance = serviceInstance.get();
      closer.register(instance);
      try {
         try {
            return getVM(vmName, instance.getInstance().getRootFolder());
         } catch (Throwable t) {
            throw closer.rethrow(t);
         } finally {
            closer.close();
         }
      } catch (IOException e) {
         Throwables.propagateIfPossible(e);
      }
      return null;
   }

   @Override
   public void destroyNode(String vmName) {
      Closer closer = Closer.create();
      VSphereServiceInstance instance = serviceInstance.get();
      closer.register(instance);
      try {
         try {
            VirtualMachine virtualMachine = getVM(vmName, instance.getInstance().getRootFolder());
            Task powerOffTask = virtualMachine.powerOffVM_Task();
            if (powerOffTask.waitForTask().equals(Task.SUCCESS))
               logger.debug(String.format("VM %s powered off", vmName));
            else
               logger.debug(String.format("VM %s could not be powered off", vmName));

            Task destroyTask = virtualMachine.destroy_Task();
            if (destroyTask.waitForTask().equals(Task.SUCCESS))
               logger.debug(String.format("VM %s destroyed", vmName));
            else
               logger.debug(String.format("VM %s could not be destroyed", vmName));
         } catch (Exception e) {
            logger.error("Can't destroy vm " + vmName, e);
            throw closer.rethrow(e);
         } finally {
            closer.close();
         }
      } catch (IOException e) {
         logger.trace(e.getMessage(), e);
      }

   }

   @Override
   public void rebootNode(String vmName) {
      VirtualMachine virtualMachine = getNode(vmName);

      try {
         virtualMachine.rebootGuest();
      } catch (Exception e) {
         logger.error("Can't reboot vm " + vmName, e);
         propagate(e);
      }
      logger.debug(vmName + " rebooted");
   }

   @Override
   public void resumeNode(String vmName) {
      VirtualMachine virtualMachine = getNode(vmName);

      if (virtualMachine.getRuntime().getPowerState().equals(VirtualMachinePowerState.poweredOff)) {
         try {
            Task task = virtualMachine.powerOnVM_Task(null);
            if (task.waitForTask().equals(Task.SUCCESS))
               logger.debug(virtualMachine.getName() + " resumed");
         } catch (Exception e) {
            logger.error("Can't resume vm " + vmName, e);
            propagate(e);
         }

      } else
         logger.debug(vmName + " can't be resumed");
   }

   @Override
   public void suspendNode(String vmName) {
      VirtualMachine virtualMachine = getNode(vmName);

      try {
         Task task = virtualMachine.suspendVM_Task();
         if (task.waitForTask().equals(Task.SUCCESS))
            logger.debug(vmName + " suspended");
         else
            logger.debug(vmName + " can't be suspended");
      } catch (Exception e) {
         logger.error("Can't suspend vm " + vmName, e);
         propagate(e);
      }
   }

   @Override
   public Image getImage(String imageName) {
      Closer closer = Closer.create();
      VSphereServiceInstance instance = serviceInstance.get();
      closer.register(instance);
      try {
         try {
            return virtualMachineToImage.apply(getVMwareTemplate(imageName, instance.getInstance().getRootFolder()));
         } catch (Throwable t) {
            throw closer.rethrow(t);
         } finally {
            closer.close();
         }
      } catch (IOException e) {
         Throwables.propagateIfPossible(e);
      }
      return null;
   }

   private VirtualMachine cloneMaster(VirtualMachine master, String tag, String name, VirtualMachineCloneSpec cloneSpec) {
      VirtualMachine cloned = null;
      try {

         Task task = master.cloneVM_Task((Folder) master.getParent(), name, cloneSpec);
         String result = task.waitForTask();
         if (result.equals(Task.SUCCESS)) {
            cloned = (VirtualMachine) new InventoryNavigator((Folder) master.getParent()).searchManagedEntity("VirtualMachine", name);
         } else {
            String errorMessage = task.getTaskInfo().getError().getLocalizedMessage();
            logger.error(errorMessage);
         }
      } catch (Exception e) {
         logger.error("Can't clone vm", e);
         propagate(e);
      }
      return checkNotNull(cloned, "cloned");
   }

   private ResourcePool tryFindResourcePool(Folder folder, String hostname) {
      Iterable<ResourcePool> resourcePools = ImmutableSet.<ResourcePool>of();
      try {
         ManagedEntity[] resourcePoolEntities = new InventoryNavigator(folder).searchManagedEntities("ResourcePool");
         resourcePools = Iterables.transform(Arrays.asList(resourcePoolEntities), new Function<ManagedEntity, ResourcePool>() {
            public ResourcePool apply(ManagedEntity input) {
               return (ResourcePool) input;
            }
         });
         Optional<ResourcePool> optionalResourcePool = Iterables.tryFind(resourcePools, VSpherePredicate.isResourcePoolOf(hostname));
         return optionalResourcePool.orNull();
      } catch (Exception e) {
         logger.error("Problem in finding a valid resource pool", e);
      }
      return null;
   }

   private VirtualMachine getVM(String vmName, Folder nodesFolder) {
      logger.trace(">> search for vm with name : " + vmName);
      VirtualMachine vm = null;
      try {
         vm = (VirtualMachine) new InventoryNavigator(nodesFolder).searchManagedEntity("VirtualMachine", vmName);
      } catch (Exception e) {
         logger.error("Can't find vm", e);
         propagate(e);
      }
      return vm;
   }

   private VirtualMachine getVMwareTemplate(String imageName, Folder rootFolder) {
      VirtualMachine image = null;
      try {
         VirtualMachine node = getVM(imageName, rootFolder);
         if (VSpherePredicate.isTemplatePredicate.apply(node))
            image = node;
      } catch (NullPointerException e) {
         logger.error("cannot find an image called " + imageName, e);
         throw e;
      }
      return checkNotNull(image, "image");
   }

   private List<VirtualDeviceConfigSpec> createNicSpec(Set<NetworkConfig> networks) {
      List<VirtualDeviceConfigSpec> nics = Lists.newArrayList();
      int i = 0;
      for (NetworkConfig net : networks) {
         VirtualDeviceConfigSpec nicSpec = new VirtualDeviceConfigSpec();
         nicSpec.setOperation(VirtualDeviceConfigSpecOperation.add);

         VirtualEthernetCard nic = new VirtualVmxnet3();
         VirtualEthernetCardNetworkBackingInfo nicBacking = new VirtualEthernetCardNetworkBackingInfo();
         nicBacking.setDeviceName(net.getNetworkName());
         Description info = new Description();
         info.setLabel(net.getNicName());
         info.setLabel("" + i);
         info.setSummary(net.getNetworkName());
         nic.setDeviceInfo(info);
         nic.setAddressType(net.getAddressType());
         nic.setBacking(nicBacking);
         nic.setKey(i);
         nicSpec.setDevice(nic);
         nics.add(nicSpec);
         i++;
      }
      return nics;
   }

   private void markVirtualMachineAsTemplate(VirtualMachine vm) throws RemoteException {

      lock.lock();
      try {
         if (!vm.getConfig().isTemplate())
            vm.markAsTemplate();
      } finally {
         lock.unlock();
      }
   }


   private void markTemplateAsVirtualMachine(VirtualMachine master, ResourcePool resourcePool, HostSystem host)
           throws RemoteException, TaskInProgress, InterruptedException {
      lock.lock();
      try {
         if (master.getConfig().isTemplate())
            master.markAsVirtualMachine(resourcePool, host);
      } finally {
         lock.unlock();
      }
   }


   private GuestNicInfo[] getGuestNicInfo(VirtualMachine virtualMachine) {
      GuestNicInfo[] nics = virtualMachine.getGuest().getNet();
      if (nics == null) {
         VSpherePredicate.WAIT_FOR_NIC(1000 * 60 * 5, TimeUnit.MILLISECONDS).apply(virtualMachine);
         nics = virtualMachine.getGuest().getNet();
      }
      return nics;
   }

   private String getBOOTPROTO(String type) {
      if ("generated".equals(type))
         return "dhcp";
      else
         return "none";
   }

   private void waitForPort(VirtualMachine vm, int port, long timeout) {
      GuestOperationsManager gom = serviceInstance.get().getInstance().getGuestOperationsManager();
      GuestAuthManager gam = gom.getAuthManager(vm);
      NamePasswordAuthentication npa = new NamePasswordAuthentication();
      npa.setUsername("root");
      npa.setPassword(vmInitPassword);
      GuestProgramSpec gps = new GuestProgramSpec();
      gps.programPath = "/bin/sh";

      StringBuilder openPortScript = new StringBuilder("netstat -nat | grep LIST | grep -q ':" + port + " ' && touch /tmp/portopen.txt");


      gps.arguments = "-c \"" + openPortScript.toString() + "\"";

      List<String> env = Lists.newArrayList("PATH=/usr/local/sbin:/usr/local/bin:/sbin:/bin:/usr/sbin:/usr/bin:/root/bin",
              "SHELL=/bin/bash");

      gps.setEnvVariables(env.toArray(new String[env.size()]));
      GuestProcessManager gpm = gom.getProcessManager(vm);
      try {
         long pid = gpm.startProgramInGuest(npa, gps);

         GuestFileManager guestFileManager = vm.getServerConnection().getServiceInstance().getGuestOperationsManager().getFileManager(vm);
         FileTransferInformation fti = guestFileManager.initiateFileTransferFromGuest(npa, "/tmp/portopen.txt");
         if (fti.getSize() == 0)
            logger.debug(" ");
      } catch (RemoteException e) {
         logger.error(e.getMessage(), e);
         Throwables.propagate(e);
      }
   }


   private void postConfiguration(VirtualMachine vm, String name, String group, Set<NetworkConfig> networkConfigs) {
      if (!vm.getConfig().isTemplate())
         VSpherePredicate.WAIT_FOR_VMTOOLS(10 * 1000 * 10, TimeUnit.MILLISECONDS).apply(vm);

      GuestOperationsManager gom = serviceInstance.get().getInstance().getGuestOperationsManager();
      GuestAuthManager gam = gom.getAuthManager(vm);
      NamePasswordAuthentication npa = new NamePasswordAuthentication();
      npa.setUsername("root");
      npa.setPassword(vmInitPassword);
      GuestProgramSpec gps = new GuestProgramSpec();
      gps.programPath = "/bin/sh";

      StringBuilder ethScript = new StringBuilder("rm -f /etc/sysconfig/network-scripts/ifcfg-eth*;");

      int index = 0;
      for (NetworkConfig config : networkConfigs) {
         ethScript.append("echo 'DEVICE=eth" + index);
         ethScript.append("\nTYPE=Ethernet");
         ethScript.append("\nONBOOT=yes");
         ethScript.append("\nNM_CONTROLLED=yes");
         ethScript.append("\nBOOTPROTO=" + getBOOTPROTO(config.getAddressType()) + "' > /etc/sysconfig/network-scripts/ifcfg-eth" + index + ";");
         index++;
      }

      ethScript.append("sed -i \"/HOSTNAME/d\" /etc/sysconfig/network;");
      ethScript.append("echo \"HOSTNAME=" + name + "\" >> /etc/sysconfig/network;");
      ethScript.append("hostname " + name + ";");


      ethScript.append("\necho 'fdisk /dev/sdb <<EOF");
      ethScript.append("\np");
      ethScript.append("\nn");
      ethScript.append("\np");
      ethScript.append("\n1");
      ethScript.append("\n");
      ethScript.append("\n");
      ethScript.append("\nt");
      ethScript.append("\n8e");
      ethScript.append("\nw");
      ethScript.append("\nEOF' > /tmp/fdisk.sh;");
      ethScript.append("\nchmod 0700 /tmp/fdisk.sh >> /tmp/jclouds-init.log 2>&1;");
      ethScript.append("\n/tmp/fdisk.sh >> /tmp/jclouds-init.log 2>&1;");

      ethScript.append("\npvcreate /dev/sdb1 >> /tmp/jclouds-init.log 2>&1;");
      ethScript.append("\nvgextend VolGroup /dev/sdb1 >> /tmp/jclouds-init.log 2>&1;");

      ethScript.append("\nvgdisplay VolGroup >> /tmp/volgroup 2>&1;");

      ethScript.append("\nawk 'BEGIN { free=0; alloc=0; } /Alloc/ { alloc=\\$7 } /Free/ { free=\\$7 } END { print \\\"-L+\\\" free - alloc \\\"G\\\" }' /tmp/volgroup | xargs lvextend /dev/VolGroup/lv_root >> /tmp/jclouds-init.log 2>&1;");
      ethScript.append("\nresize2fs /dev/VolGroup/lv_root >> /tmp/jclouds-init.log 2>&1;");

      ethScript.append("\nmkdir -p ~/.ssh;");
      ethScript.append("\nrestorecon -FRvv ~/.ssh;");

      ethScript.append("\nservice network reload;");

      ethScript.append("\nrm -f /tmp/fdisk.sh;");
      ethScript.append("\nrm -f /tmp/volgroup;");

      gps.arguments = "-c \"" + ethScript.toString() + "\"";

      List<String> env = Lists.newArrayList("PATH=/usr/local/sbin:/usr/local/bin:/sbin:/bin:/usr/sbin:/usr/bin:/root/bin",
              "SHELL=/bin/bash");

      gps.setEnvVariables(env.toArray(new String[env.size()]));
      GuestProcessManager gpm = gom.getProcessManager(vm);
      try {
         long pid = gpm.startProgramInGuest(npa, gps);
         GuestProcessInfo[] processInfos = gpm.listProcessesInGuest(npa, new long[]{pid});
         if (null != processInfos) {
            for (GuestProcessInfo processInfo : processInfos) {
               while (processInfo.getExitCode() == null) {
                  processInfos = gpm.listProcessesInGuest(npa, new long[]{pid});
                  processInfo = processInfos[0];
               }
               if (processInfo.getExitCode() != 0) {
                  logger.error("failed to run init script on node ( " + name + " ) exit code : " + processInfo.getExitCode());
                  Throwables.propagate(new Exception("Failed to customize vm ( " + name + " )"));
               }
            }
         }
         logger.trace("<< process pid : " + pid);
      } catch (RemoteException e) {
         logger.error(e.getMessage(), e);
         Throwables.propagate(e);
      }

   }
}
