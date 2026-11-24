package justepicdimensions.worldgen;

/** Implemented by the pinned native construction bridge. */
public interface JEDWorldgenAccess {
    JEDWorldgenContext jed$worldgen();
    void jed$worldgen(JEDWorldgenContext context);
}
