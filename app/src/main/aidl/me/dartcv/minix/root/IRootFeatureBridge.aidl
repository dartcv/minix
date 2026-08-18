package me.dartcv.minix.root;

interface IRootFeatureBridge {
    int getProtocolVersion();
    int getUid();
    int getServicePid();
    String getAbi();
    String getServiceProcessName();

    int findTargetPid(String packageName);
    String readProcessName(int pid);
    boolean verifyTargetProcess(int pid, String packageName);

    boolean openOrRefreshTarget(String packageName);
    void closeTarget();
    String getTargetPackage();
    int getTargetPid();
    int getTargetUid();
    String getTargetStartTimeTicks();
    String getTargetSummary();

    String getTargetProbeStatus();
    String getTargetProbeMessage();
    int getTargetProbeRegionCount();
    int getTargetProbeModuleCount();
    int getTargetProbeMemoryReadableModuleCount();
    long getTargetProbeMemoryReadBytes();
    int getTargetProbeMemoryElfHeaderCount();
    String getTargetProbeFingerprint();
    boolean isTargetProbeTruncated();
    String[] getTargetProbeModules();

    String getReadOnlyFieldProfileStatus();
    String getReadOnlyFieldProfileSummary();
    String getReadOnlyFieldProfileId();
    String getReadOnlyFieldTargetVersion();
    boolean refreshReadOnlyFields();

    String getLifeStateReadStatus();
    boolean hasLifeStateValue();
    int getLifeStateValue();
    String getLifeStateReadMessage();

    String getKillCountReadStatus();
    boolean hasKillCountValue();
    int getKillCountValue();
    String getKillCountReadMessage();

    String getDataLongSelector1ReadStatus();
    boolean hasDataLongSelector1Value();
    long getDataLongSelector1Value();
    String getDataLongSelector1ReadMessage();

    String getInjectionProfileStatus();
    String getInjectionProfileSummary();
    String getInjectionProfileId();
    String getInjectionTargetVersion();
    String getInjectionRequiredAbi();
    String getLastInjectionApplyStatus();
    String getLastInjectionFeatureId();
    String getLastInjectionMessage();

    boolean armAntiFlashForPackage(String packageName);
    String getAntiFlashStateJson();

    boolean setPlayerPosition(int x, int y, int z);
    String getLastPlayerPositionStatus();
    boolean hasLastPlayerPositionX();
    int getLastPlayerPositionX();
    boolean hasLastPlayerPositionY();
    int getLastPlayerPositionY();
    boolean hasLastPlayerPositionZ();
    int getLastPlayerPositionZ();
    int getLastPlayerPositionAppliedAxisCount();
    String getLastPlayerPositionProfileId();
    String getLastPlayerPositionMessage();

    boolean searchId(long requestedId);
    String getLastSearchIdStatus();
    long getLastSearchIdRequestedId();
    boolean hasLastSearchIdSlotIndex();
    int getLastSearchIdSlotIndex();
    String getLastSearchIdInvalidReason();
    String getLastSearchIdProcessStartTimeTicks();
    String getLastSearchIdMessage();

    boolean setFeatureEnabled(String featureId, boolean enabled);
    boolean isFeatureEnabled(String featureId);
    String[] getEnabledFeatureIds();
    String[] getSupportedFeatureIds();
}
