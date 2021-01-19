package org.ovirt.engine.core.bll;

import static org.ovirt.engine.core.bll.storage.disk.image.DisksFilter.ONLY_ACTIVE;
import static org.ovirt.engine.core.bll.storage.disk.image.DisksFilter.ONLY_NOT_SHAREABLE;
import static org.ovirt.engine.core.bll.storage.disk.image.DisksFilter.ONLY_SNAPABLE;

import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.enterprise.inject.Instance;
import javax.enterprise.inject.Typed;
import javax.inject.Inject;

import org.apache.commons.lang.StringUtils;
import org.ovirt.engine.core.bll.context.CommandContext;
import org.ovirt.engine.core.bll.storage.disk.image.DisksFilter;
import org.ovirt.engine.core.bll.storage.utils.VdsCommandsHelper;
import org.ovirt.engine.core.bll.tasks.CommandCoordinatorUtil;
import org.ovirt.engine.core.bll.tasks.interfaces.CommandCallback;
import org.ovirt.engine.core.bll.utils.PermissionSubject;
import org.ovirt.engine.core.bll.validator.storage.DiskExistenceValidator;
import org.ovirt.engine.core.bll.validator.storage.DiskImagesValidator;
import org.ovirt.engine.core.common.AuditLogType;
import org.ovirt.engine.core.common.FeatureSupported;
import org.ovirt.engine.core.common.VdcObjectType;
import org.ovirt.engine.core.common.action.ActionParametersBase;
import org.ovirt.engine.core.common.action.ActionReturnValue;
import org.ovirt.engine.core.common.action.ActionType;
import org.ovirt.engine.core.common.action.AddVolumeBitmapCommandParameters;
import org.ovirt.engine.core.common.action.LockProperties;
import org.ovirt.engine.core.common.action.VmBackupParameters;
import org.ovirt.engine.core.common.businessentities.ActionGroup;
import org.ovirt.engine.core.common.businessentities.VDS;
import org.ovirt.engine.core.common.businessentities.VMStatus;
import org.ovirt.engine.core.common.businessentities.VdsmImageLocationInfo;
import org.ovirt.engine.core.common.businessentities.VmBackup;
import org.ovirt.engine.core.common.businessentities.VmBackupPhase;
import org.ovirt.engine.core.common.businessentities.VmCheckpoint;
import org.ovirt.engine.core.common.businessentities.VmDevice;
import org.ovirt.engine.core.common.businessentities.VmDeviceId;
import org.ovirt.engine.core.common.businessentities.storage.Disk;
import org.ovirt.engine.core.common.businessentities.storage.DiskBackupMode;
import org.ovirt.engine.core.common.businessentities.storage.DiskImage;
import org.ovirt.engine.core.common.businessentities.storage.ImageStatus;
import org.ovirt.engine.core.common.errors.EngineException;
import org.ovirt.engine.core.common.errors.EngineMessage;
import org.ovirt.engine.core.common.locks.LockingGroup;
import org.ovirt.engine.core.common.utils.Pair;
import org.ovirt.engine.core.common.vdscommands.VDSCommandType;
import org.ovirt.engine.core.common.vdscommands.VDSReturnValue;
import org.ovirt.engine.core.common.vdscommands.VmBackupVDSParameters;
import org.ovirt.engine.core.compat.CommandStatus;
import org.ovirt.engine.core.compat.Guid;
import org.ovirt.engine.core.dal.dbbroker.auditloghandling.AuditLogDirector;
import org.ovirt.engine.core.dao.BaseDiskDao;
import org.ovirt.engine.core.dao.DiskDao;
import org.ovirt.engine.core.dao.VmBackupDao;
import org.ovirt.engine.core.dao.VmCheckpointDao;
import org.ovirt.engine.core.dao.VmDao;
import org.ovirt.engine.core.dao.VmDeviceDao;
import org.ovirt.engine.core.di.Injector;
import org.ovirt.engine.core.utils.ReplacementUtils;
import org.ovirt.engine.core.utils.transaction.TransactionSupport;
import org.ovirt.engine.core.vdsbroker.irsbroker.VmBackupInfo;

@DisableInPrepareMode
@NonTransactiveCommandAttribute
public class StartVmBackupCommand<T extends VmBackupParameters> extends VmCommand<T>
        implements SerialChildExecutingCommand {

    @Inject
    private AuditLogDirector auditLogDirector;
    @Inject
    private DiskDao diskDao;
    @Inject
    private VmDao vmDao;
    @Inject
    private VmBackupDao vmBackupDao;
    @Inject
    private VmCheckpointDao vmCheckpointDao;
    @Inject
    private BaseDiskDao baseDiskDao;
    @Inject
    private VmDeviceDao vmDeviceDao;
    @Inject
    private CommandCoordinatorUtil commandCoordinatorUtil;
    @Inject
    private VdsCommandsHelper vdsCommandsHelper;

    private List<DiskImage> disksList;
    private VmCheckpoint vmCheckpointsLeaf;
    private Set<Guid> fromCheckpointDisksIds;

    @Inject
    @Typed(SerialChildCommandsExecutionCallback.class)
    private Instance<SerialChildCommandsExecutionCallback> callbackProvider;

    public StartVmBackupCommand(T parameters, CommandContext cmdContext) {
        super(parameters, cmdContext);
    }

    @Override
    public void init() {
        setVmId(getParameters().getVmBackup().getVmId());
        setVdsId(getVm().getRunOnVds());
    }

    @Override
    protected boolean validate() {
        DiskExistenceValidator diskExistenceValidator = createDiskExistenceValidator(getDiskIds());
        if (!validate(diskExistenceValidator.disksNotExist())) {
            return false;
        }

        DiskImagesValidator diskImagesValidator = createDiskImagesValidator(getDisks());
        if (!validate(diskImagesValidator.diskImagesNotLocked())) {
            return false;
        }

        if (!validate(allDisksPlugged())) {
            return false;
        }

        if (getParameters().getVmBackup().getFromCheckpointId() != null) {
            if (!FeatureSupported.isIncrementalBackupSupported(getCluster().getCompatibilityVersion())) {
                return failValidation(EngineMessage.ACTION_TYPE_FAILED_INCREMENTAL_BACKUP_NOT_SUPPORTED);
            }

            if (!validate(diskImagesValidator.incrementalBackupEnabled())) {
                return false;
            }

            if (vmCheckpointDao.get(getParameters().getVmBackup().getFromCheckpointId()) == null) {
                return failValidation(EngineMessage.ACTION_TYPE_FAILED_CHECKPOINT_NOT_EXIST,
                        String.format("$checkpointId %s", getParameters().getVmBackup().getFromCheckpointId()));
            }
            if (!FeatureSupported.isBackupModeAndBitmapsOperationsSupported(getCluster().getCompatibilityVersion())) {
                // Due to bz #1829829, Libvirt doesn't handle the case of mixing full and incremental
                // backup under the same operation. This situation can happen when adding a new disk
                // to a VM that already has a previous backup.
                Set<Guid> diskIds = getDisksNotInPreviousCheckpoint();
                if (!diskIds.isEmpty()) {
                    return failValidation(
                            EngineMessage.ACTION_TYPE_FAILED_MIXED_INCREMENTAL_AND_FULL_BACKUP_NOT_SUPPORTED,
                            String.format("$diskIds %s", diskIds));
                }
            }
        }

        if (!getVm().getStatus().isQualifiedForVmBackup()) {
            return failValidation(EngineMessage.CANNOT_START_BACKUP_VM_SHOULD_BE_IN_UP_OR_DOWN_STATUS);
        }
        if (isLiveBackup()) {
            if (!getVds().isBackupEnabled()) {
                return failValidation(EngineMessage.CANNOT_START_BACKUP_NOT_SUPPORTED_BY_VDS,
                        String.format("$vdsName %s", getVdsName()));
            }
        } else {
            if (!FeatureSupported.isIncrementalBackupSupported(getCluster().getCompatibilityVersion())) {
                return failValidation(EngineMessage.ACTION_TYPE_FAILED_INCREMENTAL_BACKUP_NOT_SUPPORTED);
            }
            if (!FeatureSupported.isBackupModeAndBitmapsOperationsSupported(getCluster().getCompatibilityVersion())) {
                return failValidation(EngineMessage.ACTION_TYPE_BITMAPS_OPERATION_ARE_NOT_SUPPORTED);
            }
        }
        if (!vmBackupDao.getAllForVm(getVmId()).isEmpty()) {
            return failValidation(EngineMessage.CANNOT_START_BACKUP_ALREADY_IN_PROGRESS);
        }
        return true;
    }

    public Set<Guid> getDisksNotInPreviousCheckpoint() {
        return getDiskIds().stream()
                .filter(diskId -> !getFromCheckpointDisksIds().contains(diskId))
                .collect(Collectors.toSet());
    }

    public ValidationResult allDisksPlugged() {
        List<Guid> unpluggedDisks = getParameters().getVmBackup().getDisks()
                .stream()
                .map(diskImage -> vmDeviceDao.get(new VmDeviceId(diskImage.getId(), getVmId())))
                .filter(vmDevice -> !vmDevice.isPlugged())
                .map(VmDevice::getDeviceId)
                .collect(Collectors.toList());
        if (!unpluggedDisks.isEmpty()) {
            return new ValidationResult(EngineMessage.ACTION_TYPE_FAILED_DISKS_ARE_NOT_ACTIVE,
                    ReplacementUtils.createSetVariableString("vmName", getVm().getName()),
                    ReplacementUtils.createSetVariableString("diskIds", StringUtils.join(unpluggedDisks, ", ")));
        }
        return ValidationResult.VALID;
    }

    @Override
    protected void executeCommand() {
        VmBackup vmBackup = getParameters().getVmBackup();

        // sets the backup disks with the disks from the DB
        // that contain all disk image data
        vmBackup.setDisks(getDisks());

        log.info("Creating VmBackup entity for VM '{}'", vmBackup.getVmId());
        Guid vmBackupId = createVmBackup();
        log.info("Created VmBackup entity '{}'", vmBackupId);

        if (isLiveBackup()) {
            log.info("Redefine previous VM checkpoints for VM '{}'", vmBackup.getVmId());
            ActionReturnValue returnValue = runInternalAction(ActionType.RedefineVmCheckpoint, getParameters());
            if (!returnValue.getSucceeded()) {
                addCustomValue("backupId", vmBackupId.toString());
                auditLogDirector.log(this, AuditLogType.VM_INCREMENTAL_BACKUP_FAILED_FULL_VM_BACKUP_NEEDED);
                setCommandStatus(CommandStatus.FAILED);
                return;
            }
            log.info("Successfully redefined previous VM checkpoints for VM '{}'", vmBackup.getVmId());
        }

        if (FeatureSupported.isIncrementalBackupSupported(getCluster().getCompatibilityVersion())
                && !isBackupContainsRawDisksOnly()) {
            log.info("Creating VmCheckpoint entity for VM '{}'", vmBackup.getVmId());
            Guid vmCheckpointId = createVmCheckpoint();
            log.info("Created VmCheckpoint entity '{}'", vmCheckpointId);

            // Set the the created checkpoint ID only in the parameters and not in the
            // VM backup DB entity. The VM backup DB entity will be updated once the
            // checkpoint will be created by the host.
            getParameters().setToCheckpointId(vmCheckpointId);
        } else {
            log.info("Skip checkpoint creation for VM '{}'", vmBackup.getVmId());
        }

        updateVmBackupPhase(VmBackupPhase.STARTING);
        persistCommandIfNeeded();
        setActionReturnValue(vmBackupId);
        setSucceeded(true);
    }

    @Override
    public boolean performNextOperation(int completedChildCount) {
        restoreCommandState();

        switch (getParameters().getVmBackup().getPhase()) {
            case STARTING:
                boolean isBackupSucceeded = isLiveBackup() ? runLiveVmBackup() : runColdVmBackup();
                if (isBackupSucceeded) {
                    updateVmBackupPhase(VmBackupPhase.READY);
                    log.info("Ready to start image transfers");
                } else {
                    setCommandStatus(CommandStatus.FAILED);
                }
                break;

            case READY:
                return true;

            case FINALIZING:
                finalizeVmBackup();
                setCommandStatus(CommandStatus.SUCCEEDED);
                break;
        }
        persistCommandIfNeeded();
        return true;
    }

    private boolean runColdVmBackup() {
        lockDisks();
        setHostForColdBackupOperation();
        if (getParameters().getVdsRunningOn() == null) {
            log.error("Failed to find host to run cold backup operation for VM '{}'",
                    getParameters().getVmBackup().getVmId());
            return false;
        }
        getParameters().getVmBackup().setToCheckpointId(getParameters().getToCheckpointId());

        for (DiskImage diskImage : getParameters().getVmBackup().getDisks()) {
            if (!diskImage.isQcowFormat()) {
                continue;
            }

            VdsmImageLocationInfo locationInfo = new VdsmImageLocationInfo(
                    diskImage.getStorageIds().get(0),
                    diskImage.getId(),
                    diskImage.getImageId(),
                    null);

            AddVolumeBitmapCommandParameters parameters =
                    new AddVolumeBitmapCommandParameters(
                            getStoragePoolId(),
                            locationInfo,
                            getParameters().getToCheckpointId().toString());
            parameters.setEndProcedure(ActionParametersBase.EndProcedure.COMMAND_MANAGED);
            parameters.setParentCommand(getActionType());
            parameters.setParentParameters(getParameters());

            ActionReturnValue returnValue = runInternalActionWithTasksContext(ActionType.AddVolumeBitmap, parameters);
            if (!returnValue.getSucceeded()) {
                log.error("Failed to add bitmap to disk '{}'", diskImage.getId());
                return false;
            }
        }
        updateVmBackupCheckpoint(null);
        return true;
    }

    private void setHostForColdBackupOperation() {
        if (getParameters().getVdsRunningOn() == null) {
            getParameters().setVdsRunningOn(
                    vdsCommandsHelper.getHostForExecution(getStoragePoolId(), VDS::isColdBackupEnabled));
            persistCommandIfNeeded();
        }
    }

    private boolean runLiveVmBackup() {
        lockDisks();
        VmBackupInfo vmBackupInfo = null;
        if (!getParameters().isBackupInitiated()) {
            getParameters().setBackupInitiated(true);
            persistCommandIfNeeded();
            vmBackupInfo = performVmBackupOperation(VDSCommandType.StartVmBackup);
        }

        if (vmBackupInfo == null || vmBackupInfo.getDisks() == null) {
            // Check if backup already started at the host
            if (!getParameters().isBackupInitiated()) {
                // backup operation didn't start yet, fail the operation
                return false;
            }

            vmBackupInfo = recoverFromMissingBackupInfo();
            if (vmBackupInfo == null) {
                return false;
            }
        } else if (vmBackupInfo.getCheckpoint() == null && !isBackupContainsRawDisksOnly()
                && FeatureSupported.isIncrementalBackupSupported(getCluster().getCompatibilityVersion())) {
            vmBackupInfo = recoverFromMissingCheckpointInfo();
            if (vmBackupInfo == null) {
                return false;
            }
        }

        if (vmBackupInfo.getCheckpoint() != null) {
            updateVmBackupCheckpoint(vmBackupInfo);
        }
        storeBackupsUrls(vmBackupInfo.getDisks());
        return true;
    }

    private VmBackupInfo recoverFromMissingBackupInfo() {
        // Try to recover by fetching the backup info
        VmBackupInfo vmBackupInfo = performVmBackupOperation(VDSCommandType.GetVmBackupInfo);
        if (vmBackupInfo == null || vmBackupInfo.getDisks() == null) {
            log.error("Failed to start VM '{}' backup '{}' on the host",
                    getVmId(),
                    getParameters().getVmBackup().getId());
            return null;
        }
        return vmBackupInfo;
    }

    private VmBackupInfo recoverFromMissingCheckpointInfo() {
        // Try to fetch the checkpoint XML again
        VmBackupInfo vmBackupInfo = performVmBackupOperation(VDSCommandType.GetVmBackupInfo);
        if (vmBackupInfo == null || vmBackupInfo.getCheckpoint() == null) {
            // Best effort - stop the backup
            runInternalAction(ActionType.StopVmBackup, getParameters());
            addCustomValue("backupId", getParameters().getVmBackup().getId().toString());
            auditLogDirector.log(this, AuditLogType.VM_BACKUP_STOPPED);
            log.error("Failed to fetch checkpoint id: '{}' XML from Libvirt, VM id: '{}' "
                            + "backup chain cannot be used anymore and a full backup should be taken.",
                    getParameters().getVmBackup().getToCheckpointId(), getVmId());
            return null;
        }
        return vmBackupInfo;
    }

    private void finalizeVmBackup() {
        cleanDisksBackupModeIfSupported();
        unlockDisks();
    }

    private void removeCheckpointFromDb() {
        Guid vmCheckpointId = getParameters().getToCheckpointId();
        log.info("Remove VmCheckpoint entity '{}'", vmCheckpointId);

        TransactionSupport.executeInNewTransaction(() -> {
            vmCheckpointDao.remove(vmCheckpointId);
            return null;
        });
    }

    private Guid createVmBackup() {
        final VmBackup vmBackup = getParameters().getVmBackup();
        vmBackup.setId(getCommandId());
        vmBackup.setHostId(getVdsId());
        vmBackup.setPhase(VmBackupPhase.INITIALIZING);
        vmBackup.setCreationDate(new Date());
        getParameters().setVmBackup(vmBackup);
        TransactionSupport.executeInNewTransaction(() -> {
            vmBackupDao.save(vmBackup);
            getParameters().getVmBackup().getDisks().forEach(
                    disk -> {
                        setDiskBackupModeIfSupported(disk);
                        vmBackupDao.addDiskToVmBackup(vmBackup.getId(), disk.getId());
                    });
            return null;
        });
        persistCommandIfNeeded();
        return vmBackup.getId();
    }

    private void setDiskBackupModeIfSupported(DiskImage disk) {
        if (FeatureSupported.isBackupModeAndBitmapsOperationsSupported(getCluster().getCompatibilityVersion())) {
            DiskBackupMode diskBackupMode =
                    getBackupModeForDisk(disk.getId(),
                            getParameters().getVmBackup().getFromCheckpointId());
            disk.setBackupMode(diskBackupMode);
            baseDiskDao.update(disk);
        }
    }

    private DiskBackupMode getBackupModeForDisk(Guid diskId, Guid checkpointId) {
        if (checkpointId == null) {
            return DiskBackupMode.Full;
        } else if (!getFromCheckpointDisksIds().contains(diskId)) {
            log.warn("Disk ID {} doesn't include in checkpoint ID {}, a full back will be taken for it.",
                    diskId, checkpointId);
            return DiskBackupMode.Full;
        }
        return DiskBackupMode.Incremental;
    }

    public Set<Guid> getFromCheckpointDisksIds() {
        if (fromCheckpointDisksIds == null) {
            List<DiskImage> checkpointDisks =
                    vmCheckpointDao.getDisksByCheckpointId(getParameters().getVmBackup().getFromCheckpointId());

            fromCheckpointDisksIds = checkpointDisks
                    .stream()
                    .map(DiskImage::getId)
                    .collect(Collectors.toCollection(HashSet::new));
        }
        return fromCheckpointDisksIds;
    }

    private Guid createVmCheckpoint() {
        final VmCheckpoint vmCheckpoint = new VmCheckpoint();

        vmCheckpoint.setId(Guid.newGuid());
        VmCheckpoint checkpointsLeaf = getVmCheckpointsLeaf();
        if (checkpointsLeaf != null) {
            vmCheckpoint.setParentId(checkpointsLeaf.getId());
        }
        vmCheckpoint.setVmId(getParameters().getVmBackup().getVmId());
        vmCheckpoint.setCreationDate(new Date());

        TransactionSupport.executeInNewTransaction(() -> {
            vmCheckpointDao.save(vmCheckpoint);
            getParameters().getVmBackup().getDisks().stream()
                    .filter(DiskImage::isQcowFormat)
                    .forEach(disk -> vmCheckpointDao.addDiskToCheckpoint(vmCheckpoint.getId(), disk.getId()));
            return null;
        });

        persistCommandIfNeeded();
        return vmCheckpoint.getId();
    }

    private boolean isBackupContainsRawDisksOnly() {
        return getParameters().getVmBackup()
                .getDisks()
                .stream()
                .noneMatch(DiskImage::isQcowFormat);
    }

    private VmBackupInfo performVmBackupOperation(VDSCommandType vdsCommandType) {
        VDSReturnValue vdsRetVal;
        // Add the created checkpoint ID
        VmBackup vmBackup = getParameters().getVmBackup();
        vmBackup.setToCheckpointId(getParameters().getToCheckpointId());
        try {
            vdsRetVal = runVdsCommand(vdsCommandType,
                    new VmBackupVDSParameters(getVdsId(), vmBackup, getParameters().isRequireConsistency()));
            if (!vdsRetVal.getSucceeded()) {
                EngineException engineException = new EngineException();
                engineException.setVdsError(vdsRetVal.getVdsError());
                throw engineException;
            }
            VmBackupInfo vmBackupInfo = (VmBackupInfo) vdsRetVal.getReturnValue();
            return vmBackupInfo;
        } catch (EngineException e) {
            log.error("Failed to execute VM backup operation '{}': {}", vdsCommandType, e);
            return null;
        }
    }

    private void storeBackupsUrls(Map<String, Object> disks) {
        disks.keySet().forEach(diskId ->
                vmBackupDao.addBackupUrlToVmBackup(getParameters().getVmBackup().getId(),
                        Guid.createGuidFromString(diskId),
                        (String) disks.get(diskId)));
    }

    private void restoreCommandState() {
        getParameters().setVmBackup(vmBackupDao.get(getParameters().getVmBackup().getId()));
        getParameters().getVmBackup().setDisks(
                vmBackupDao.getDisksByBackupId(getParameters().getVmBackup().getId()));
    }

    private void updateVmBackupPhase(VmBackupPhase phase) {
        getParameters().getVmBackup().setPhase(phase);
        vmBackupDao.update(getParameters().getVmBackup());
    }

    private void updateVmBackupCheckpoint(VmBackupInfo vmBackupInfo) {
        TransactionSupport.executeInNewTransaction(() -> {
            // Update the VmBackup to include the checkpoint ID
            vmBackupDao.update(getParameters().getVmBackup());

            if (vmBackupInfo != null) {
                // Update the vmCheckpoint to include the checkpoint XML
                vmCheckpointDao.updateCheckpointXml(getParameters().getVmBackup().getToCheckpointId(),
                        vmBackupInfo.getCheckpoint());
            }
            return null;
        });
    }

     @Override
    protected void endSuccessfully() {
        setSucceeded(true);
    }

    @Override
    protected void endWithFailure() {
        finalizeVmBackup();
        removeCheckpointFromDb();
        getReturnValue().setEndActionTryAgain(false);
        setSucceeded(true);
    }

    @Override
    public CommandCallback getCallback() {
        return callbackProvider.get();
    }

    @Override
    protected LockProperties applyLockProperties(LockProperties lockProperties) {
        return lockProperties.withScope(LockProperties.Scope.Command);
    }

    @Override
    protected void setActionMessageParameters() {
        addValidationMessage(EngineMessage.VAR__ACTION__BACKUP);
        addValidationMessage(EngineMessage.VAR__TYPE__VM);
    }

    @Override
    public List<PermissionSubject> getPermissionCheckSubjects() {
        final List<PermissionSubject> permissionList = super.getPermissionCheckSubjects();
        getParameters().getVmBackup().getDisks().forEach(
                disk -> permissionList.add(
                        new PermissionSubject(disk.getId(), VdcObjectType.Disk, ActionGroup.BACKUP_DISK)));
        return permissionList;
    }

    @Override
    protected Map<String, Pair<String, String>> getSharedLocks() {
        Map<String, Pair<String, String>> locks = new HashMap<>();
        locks.put(getParameters().getVmBackup().getVmId().toString(),
                    LockMessagesMatchUtil.makeLockingPair(LockingGroup.VM, EngineMessage.ACTION_TYPE_FAILED_VM_IS_LOCKED));
        return locks;
    }

    @Override
    protected Map<String, Pair<String, String>> getExclusiveLocks() {
        Map<String, Pair<String, String>> locks = new HashMap<>();
        getDiskIds().forEach(id -> locks.put(id.toString(),
                        LockMessagesMatchUtil.makeLockingPair(LockingGroup.DISK, EngineMessage.ACTION_TYPE_FAILED_DISK_IS_LOCKED)));
        return locks;
    }

    @Override
    public AuditLogType getAuditLogTypeValue() {
        addCustomValue("VmName", getVm().getName());
        addCustomValue("backupId", getParameters().getVmBackup().getId().toString());
        switch (getActionState()) {
            case EXECUTE:
                return AuditLogType.VM_BACKUP_STARTED;
            case END_FAILURE:
                return AuditLogType.VM_BACKUP_FAILED;
            case END_SUCCESS:
                if (!getSucceeded()) {
                    return AuditLogType.VM_BACKUP_FAILED;
                }
                if (getParameters().getVmBackup().getPhase() == VmBackupPhase.FINALIZING) {
                    return AuditLogType.VM_BACKUP_SUCCEEDED;
                }
        }
        return null;
    }

    private void lockDisks() {
        imagesHandler.updateAllDiskImagesSnapshotsStatusInTransactionWithCompensation(
                getDiskIds(),
                ImageStatus.LOCKED,
                ImageStatus.OK,
                getCompensationContext());
    }

    private void cleanDisksBackupModeIfSupported() {
        if (FeatureSupported.isBackupModeAndBitmapsOperationsSupported(getCluster().getCompatibilityVersion())) {
            TransactionSupport.executeInNewTransaction(() -> {
                getParameters().getVmBackup().getDisks().forEach(
                        disk -> {
                            disk.setBackupMode(null);
                            baseDiskDao.update(disk);
                        });
                return null;
            });
        }
    }

    private void unlockDisks() {
        imagesHandler.updateAllDiskImagesSnapshotsStatusInTransactionWithCompensation(
                getDiskIds(),
                ImageStatus.OK,
                ImageStatus.ILLEGAL,
                getCompensationContext());
    }

    protected DiskExistenceValidator createDiskExistenceValidator(Set<Guid> disksGuids) {
        return Injector.injectMembers(new DiskExistenceValidator(disksGuids));
    }

    protected DiskImagesValidator createDiskImagesValidator(List<DiskImage> disks) {
        return Injector.injectMembers(new DiskImagesValidator(disks));
    }

    public Set<Guid> getDiskIds() {
        return getParameters().getVmBackup().getDisks() == null ? Collections.emptySet() :
                getParameters().getVmBackup().getDisks().stream().map(DiskImage::getId).collect(
                        Collectors.toCollection(LinkedHashSet::new));
    }

    private List<DiskImage> getDisks() {
        if (disksList == null) {
            List<Disk> vmDisks = diskDao.getAllForVm(getVmId());
            List<DiskImage> diskImages = DisksFilter.filterImageDisks(vmDisks, ONLY_NOT_SHAREABLE,
                    ONLY_SNAPABLE, ONLY_ACTIVE);
            disksList = diskImages.stream().filter(d -> getDiskIds().contains(d.getId())).collect(Collectors.toList());
        }
        return disksList;
    }

    public VmCheckpoint getVmCheckpointsLeaf() {
        if (vmCheckpointsLeaf == null) {
            List<VmCheckpoint> vmCheckpoints = vmCheckpointDao.getAllForVm(getVmId());
            if (vmCheckpoints != null && !vmCheckpoints.isEmpty()) {
                vmCheckpointsLeaf = vmCheckpoints.get(vmCheckpoints.size() - 1);
            }
        }
        return vmCheckpointsLeaf;
    }

    private boolean isLiveBackup() {
        return getVm().getStatus() == VMStatus.Up;
    }
}
