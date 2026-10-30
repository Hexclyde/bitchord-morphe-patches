package androidx.compose.runtime;public interface SnapshotMutationPolicy {boolean equivalent(Object a,Object b);Object merge(Object previous,Object current,Object applied);}
