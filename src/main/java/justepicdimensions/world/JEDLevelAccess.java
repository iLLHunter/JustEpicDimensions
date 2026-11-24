package justepicdimensions.world;

/** Implemented by the pinned native ServerLevel construction bridge. */
public interface JEDLevelAccess {
    JEDLevelContext jed$context();
    void jed$context(JEDLevelContext context);
}
