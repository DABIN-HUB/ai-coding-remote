package com.wangbin.ai.agent.daemon.event.change;

import com.wangbin.ai.agent.contract.enums.FileChangeType;

public class FileChangeMergePolicy {

    public FileChangeType merge(FileChangeType current, FileChangeType next) {
        FileChangeType left = current == null ? FileChangeType.UNKNOWN : current;
        FileChangeType right = next == null ? FileChangeType.UNKNOWN : next;
        if (left == FileChangeType.ADDED && right == FileChangeType.MODIFIED) {
            return FileChangeType.ADDED;
        }
        if (left == FileChangeType.ADDED && right == FileChangeType.DELETED) {
            return FileChangeType.UNKNOWN;
        }
        if (left == FileChangeType.MODIFIED && right == FileChangeType.DELETED) {
            return FileChangeType.DELETED;
        }
        if (left == FileChangeType.RENAMED && right == FileChangeType.MODIFIED) {
            return FileChangeType.RENAMED;
        }
        if (right == FileChangeType.UNKNOWN) {
            return left;
        }
        return right;
    }
}
