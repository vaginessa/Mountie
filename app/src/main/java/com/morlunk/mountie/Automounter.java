/*
 * Mountie, a tool for mounting external storage on Android
 * Copyright (C) 2014 Andrew Comminos <andrew@morlunk.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.morlunk.mountie;

import android.os.Environment;

import com.morlunk.mountie.fs.Mount;
import com.morlunk.mountie.fs.MountException;
import com.morlunk.mountie.fs.MountListener;
import com.morlunk.mountie.fs.Partition;
import com.morlunk.mountie.fs.PartitionListener;
import com.morlunk.mountie.fs.UnmountListener;
import com.morlunk.mountie.fs.Volume;
import com.stericson.RootTools.execution.Command;
import com.stericson.RootTools.execution.CommandCapture;
import com.stericson.RootTools.execution.Shell;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Automatically mounts discovered partitions in /sdcard/mountie.
 * Created by andrew on 17/09/14.
 */
public class Automounter implements PartitionListener, UnmountListener, MountListener {
    /**
     * Android 4.2+'s mount directory for the primary user's external storage.
     * It's necessary to mount here to avoid the FUSE abstraction.
     */
    private static final String DATA_DIR = "/data/media/0/";

    private Shell mRootShell;
    /**
     * Observe that mEmulatedDirectory and mMountDirectory point to the same physical storage.
     * One is abstracted to the user as a FUSE share while the other is a mounted vfat partition.
     */
    private File mEmulatedDirectory;
    private File mMountDirectory;
    private MountListener mMountListener;
    private UnmountListener mUnmountListener;
    private Set<Mount> mMounts;

    /**
     * Creates a new automounter.
     * Must be registered to a {@link com.morlunk.mountie.fs.BlockDeviceObserver} in order to automount.
     * @param rootShell A root shell to execute mount commands in.
     * @param dirName The name of the directory to mount in on external storage.
     * @param mountListener A listener for newly automounted devices.
     * @param unmountListener A listener for unmounted automounted devices.
     */
    public Automounter(Shell rootShell, String dirName, MountListener mountListener, UnmountListener unmountListener) {
        mRootShell = rootShell;
        mEmulatedDirectory = new File(Environment.getExternalStorageDirectory(), dirName);
        mMountDirectory = new File(DATA_DIR, dirName);
        mMountListener = mountListener;
        mUnmountListener = unmountListener;
        mMounts = new HashSet<Mount>();
        cleanDirectory(); // Treat directory like a tmpfs and delete+unmount contents.
    }

    public void cleanDirectory() {
        for (final File file : mEmulatedDirectory.listFiles()) {
            Command mountCommand = new CommandCapture(0, "umount " + mMountDirectory.getAbsolutePath() + "/" + file.getName()) {
                @Override
                public void commandCompleted(int id, int exitcode) {
                    super.commandCompleted(id, exitcode);
                    file.delete();
                }
            };
            try {
                mRootShell.add(mountCommand);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onVolumeAdded(Volume volume) {
    }

    @Override
    public void onVolumeRemoved(Volume volume) {
    }

    @Override
    public void onPartitionAdded(Volume volume, Partition partition) {
        try {
            partition.mount(mRootShell, getDeviceMountDir(partition), this);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onPartitionRemoved(Volume volume, final Partition partition) {
        if (partition.isMounted()) {
            try {
                partition.unmountAll(mRootShell, this);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void unmountAll() {
        for (Mount mount : mMounts) {
            try {
                mount.unmount(mRootShell, this);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        mMounts.clear();
    }

    public Collection<Mount> getMounts() {
        return mMounts;
    }

    private File getEmulatedMountDir(Partition partition) throws IOException {
        File mountDir = new File(mEmulatedDirectory, partition.getUUID());
        if (!mountDir.exists() && !mountDir.mkdirs()) {
            throw new IOException("Couldn't create mount dir!");
        }
        return mountDir;
    }

    private String getDeviceMountDir(Partition partition) throws IOException {
        return mMountDirectory + "/" + getEmulatedMountDir(partition).getName();
    }

    @Override
    public void onUnmountSuccess(Mount mount) {
        mMounts.remove(mount);
        try {
            getEmulatedMountDir(mount.getDevice()).delete();
        } catch (IOException e) {
            e.printStackTrace();
        }
        mUnmountListener.onUnmountSuccess(mount);
    }

    @Override
    public void onUnmountError(Mount mount, Exception e) {
        mUnmountListener.onUnmountError(mount, e);
    }

    @Override
    public void onMountSuccess(Partition partition, Mount mount) {
        mMounts.add(mount);
        mMountListener.onMountSuccess(partition, mount);
    }

    @Override
    public void onMountError(Partition partition, MountException e) {
        mMountListener.onMountError(partition, e);
    }
}
