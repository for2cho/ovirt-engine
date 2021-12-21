package org.ovirt.engine.core.common.utils;

import java.util.Map;

import org.ovirt.engine.core.common.businessentities.ArchitectureType;
import org.ovirt.engine.core.common.businessentities.BiosType;
import org.ovirt.engine.core.common.businessentities.ChipsetType;
import org.ovirt.engine.core.common.businessentities.VM;
import org.ovirt.engine.core.common.businessentities.VmBase;
import org.ovirt.engine.core.common.businessentities.VmTemplate;
import org.ovirt.engine.core.common.config.Config;
import org.ovirt.engine.core.common.config.ConfigValues;
import org.ovirt.engine.core.compat.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VmCpuCountHelper {
    public static final int HIGH_NUMBER_OF_X86_VCPUS = 256;
    private static final int MAX_I440FX_VCPUS = 240;
    private static final int maxBitWidth = 8;
    private static final Logger log = LoggerFactory.getLogger(VmCpuCountHelper.class);

    private static int bitWidth(int n) {
        return n == 0 ? 0 : 32 - Integer.numberOfLeadingZeros(n - 1);
    }

    private static boolean canHighNumberOfX86Vcpus(BiosType biosType) {
        return biosType != null && biosType.getChipsetType() == ChipsetType.Q35;
    }

    private static Integer alignedMaxVCpu(Integer maxVCpus, Integer maxSockets, int threadsPerCore,
            int cpuPerSocket) {
        final int threadsPerSocket = cpuPerSocket * threadsPerCore;
        if (threadsPerSocket > maxVCpus) {
            return maxVCpus;
        }
        final Integer numberOfSockets = Math.min(maxSockets, maxVCpus / threadsPerSocket);
        return threadsPerSocket * numberOfSockets;
    }

    static Integer calcMaxVCpu(ArchitectureType architecture, Integer maxSockets, Integer maxVCpus, int threadsPerCore,
            int cpuPerSocket, BiosType biosType) {
        if (architecture == null || architecture == ArchitectureType.x86) {
            // As described in https://bugzilla.redhat.com/1406243#c13, the
            // maximum number of vCPUs is limited by thread and core numbers on
            // x86, to fit into an 8-bit APIC ID value organized by certain
            // rules. That limit is going to be removed once x2APIC is used.
            int oneSocketBitWidth = bitWidth(threadsPerCore) + bitWidth(cpuPerSocket);
            if (canHighNumberOfX86Vcpus(biosType)) {
                maxVCpus = alignedMaxVCpu(maxVCpus, maxSockets, threadsPerCore, cpuPerSocket);
            } else if (oneSocketBitWidth > maxBitWidth) {
                // This should be prevented by validation and never reached.
                // We handle it just for safety and to provide meaningful error messages.
                log.warn("{} cores with {} threads may be too many for the VM to be able to run", cpuPerSocket,
                        threadsPerCore);
                if (maxVCpus > MAX_I440FX_VCPUS) {
                    maxVCpus = threadsPerCore * cpuPerSocket;
                }
            } else {
                int apicIdLimit = (int) Math.pow(2, 8 - oneSocketBitWidth);
                int apicVCpusLimit = cpuPerSocket * threadsPerCore * Math.min(maxSockets, apicIdLimit);
                while (apicVCpusLimit > MAX_I440FX_VCPUS) {
                    // Note that maxVCpus must match the socket and thread counts.
                    apicVCpusLimit -= cpuPerSocket * threadsPerCore;
                }
                if (maxVCpus < apicVCpusLimit) {
                    maxVCpus = alignedMaxVCpu(maxVCpus, maxSockets, threadsPerCore, cpuPerSocket);
                } else {
                    maxVCpus = apicVCpusLimit;
                }
            }
        } else {
            maxVCpus = alignedMaxVCpu(maxVCpus, maxSockets, threadsPerCore, cpuPerSocket);
        }
        return maxVCpus;
    }

    /**
     * Computes the maximum allowed number of vCPUs that can be assigned
     * to a VM according to the specified compatibility level.
     *
     * @param vm The VM for which we want to know the maximum
     * @param compatibilityVersion The compatibility level
     * @param architecture Architecture family of the VM
     * @return The maximum supported number of vCPUs
     */
    public static Integer calcMaxVCpu(VmBase vm, Version compatibilityVersion, ArchitectureType architecture) {
        ArchitectureType architectureFamily = architecture != null ? architecture.getFamily() : null;

        Integer maxSockets = Config.getValue(ConfigValues.MaxNumOfVmSockets, compatibilityVersion.getValue());
        Map<String, Integer> archToMaxVmCpus = Config.getValue(
                ConfigValues.MaxNumOfVmCpus,
                compatibilityVersion.getValue());
        Integer maxVCpus = archToMaxVmCpus.get(architectureFamily.name());

        int threadsPerCore = vm.getThreadsPerCpu();
        int cpuPerSocket = vm.getCpuPerSocket();
        final BiosType biosType = vm.getBiosType();
        return calcMaxVCpu(architectureFamily, maxSockets, maxVCpus, threadsPerCore, cpuPerSocket, biosType);
    }

    /**
     * Computes the maximum allowed number of vCPUs that can be assigned
     * to a VM according to the specified compatibility level.
     *
     * @param vm                   The VM for which we want to know the maximum
     * @param compatibilityVersion The compatibility level
     * @return The maximum supported number of vCPUs
     */
    public static Integer calcMaxVCpu(VM vm, Version compatibilityVersion) {
        return calcMaxVCpu(vm.getStaticData(), compatibilityVersion, vm.getClusterArch());
    }

    /**
     * Computes the maximum allowed number of vCPUs that can be assigned
     * to a VM according to the specified compatibility level.
     *
     * @param vmTemplate The VM template for which we want to know the maximum
     * @param compatibilityVersion The compatibility level
     * @return The maximum supported number of vCPUs
     */
    public static Integer calcMaxVCpu(VmTemplate vmTemplate, Version compatibilityVersion) {
        return calcMaxVCpu(vmTemplate, compatibilityVersion, vmTemplate.getClusterArch());
    }

    /**
     * Validates CPU count configuration. It may not be possible to use
     * configurations with too many threads or cores.
     *
     * @param vm The VM for which we want to check the CPU count configuration
     * @param compatibilityVersion The effective VM compatibility version
     * @return Whether the CPU count configuration is valid
     */
    public static boolean validateCpuCounts(VM vm, Version compatibilityVersion, ArchitectureType architecture) {
        if ((architecture == null || architecture.getFamily() == ArchitectureType.x86)
                && !canHighNumberOfX86Vcpus(vm.getBiosType())
                && bitWidth(vm.getThreadsPerCpu()) + bitWidth(vm.getCpuPerSocket()) > maxBitWidth) {
            log.error("The CPU topology cannot fit into APIC for {} BIOS type", vm.getBiosType());
            return false;
        }
        Integer maxVCpus = calcMaxVCpu(vm.getStaticData(), compatibilityVersion, architecture);
        if (vm.getNumOfCpus() > maxVCpus) {
            log.error(
                    "Too many CPUs for the given topology, compatibility version {}, {} architecture, "
                            + "and {} BIOS type (maximum is {})",
                    compatibilityVersion, architecture, vm.getBiosType(), maxVCpus);
            return false;
        }
        return true;
    }
}
