/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.changedetection.changes;

import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.TaskExecutionHistory;
import org.gradle.api.internal.TaskInternal;
import org.gradle.api.internal.changedetection.TaskArtifactState;
import org.gradle.api.internal.changedetection.TaskArtifactStateRepository;
import org.gradle.api.internal.changedetection.rules.TaskStateChange;
import org.gradle.api.internal.changedetection.rules.TaskStateChanges;
import org.gradle.api.internal.changedetection.rules.TaskUpToDateState;
import org.gradle.api.internal.changedetection.state.FileCollectionSnapshot;
import org.gradle.api.internal.changedetection.state.FileCollectionSnapshotter;
import org.gradle.api.internal.changedetection.state.OutputFilesCollectionSnapshotter;
import org.gradle.api.internal.changedetection.state.TaskExecution;
import org.gradle.api.internal.changedetection.state.TaskHistoryRepository;
import org.gradle.api.internal.file.FileCollectionFactory;
import org.gradle.api.internal.tasks.cache.TaskCacheKey;
import org.gradle.api.tasks.incremental.IncrementalTaskInputs;
import org.gradle.internal.classloader.ClassLoaderHierarchyHasher;
import org.gradle.internal.reflect.Instantiator;

import java.util.Collection;
import java.util.Map;

public class DefaultTaskArtifactStateRepository implements TaskArtifactStateRepository {

    private final TaskHistoryRepository taskHistoryRepository;
    private final OutputFilesCollectionSnapshotter outputFilesSnapshotter;
    private final FileCollectionSnapshotter inputFilesSnapshotter;
    private final FileCollectionSnapshotter discoveredInputsSnapshotter;
    private final Instantiator instantiator;
    private final FileCollectionFactory fileCollectionFactory;
    private final ClassLoaderHierarchyHasher classLoaderHierarchyHasher;

    public DefaultTaskArtifactStateRepository(TaskHistoryRepository taskHistoryRepository, Instantiator instantiator,
                                              OutputFilesCollectionSnapshotter outputFilesSnapshotter, FileCollectionSnapshotter inputFilesSnapshotter,
                                              FileCollectionSnapshotter discoveredInputsSnapshotter, FileCollectionFactory fileCollectionFactory,
                                              ClassLoaderHierarchyHasher classLoaderHierarchyHasher) {
        this.taskHistoryRepository = taskHistoryRepository;
        this.instantiator = instantiator;
        this.outputFilesSnapshotter = outputFilesSnapshotter;
        this.inputFilesSnapshotter = inputFilesSnapshotter;
        this.discoveredInputsSnapshotter = discoveredInputsSnapshotter;
        this.fileCollectionFactory = fileCollectionFactory;
        this.classLoaderHierarchyHasher = classLoaderHierarchyHasher;
    }

    public TaskArtifactState getStateFor(final TaskInternal task) {
        return new TaskArtifactStateImpl(task, taskHistoryRepository.getHistory(task));
    }

    private class TaskArtifactStateImpl implements TaskArtifactState, TaskExecutionHistory {
        private final TaskInternal task;
        private final TaskHistoryRepository.History history;
        private boolean upToDate;
        private TaskUpToDateState states;
        private IncrementalTaskInputsInternal taskInputs;

        public TaskArtifactStateImpl(TaskInternal task, TaskHistoryRepository.History history) {
            this.task = task;
            this.history = history;
        }

        public boolean isUpToDate(Collection<String> messages) {
            if (collectChangedMessages(messages, getStates().getAllTaskChanges())) {
                upToDate = true;
                return true;
            }
            return false;
        }

        private boolean collectChangedMessages(Collection<String> messages, TaskStateChanges stateChanges) {
            boolean up2date = true;
            for (TaskStateChange stateChange : stateChanges) {
                if (messages != null) {
                    messages.add(stateChange.getMessage());
                    up2date = false;
                } else {
                    return false;
                }
            }
            return up2date;
        }

        public IncrementalTaskInputs getInputChanges() {
            assert !upToDate : "Should not be here if the task is up-to-date";

            if (canPerformIncrementalBuild()) {
                taskInputs = instantiator.newInstance(ChangesOnlyIncrementalTaskInputs.class, getStates().getInputFilesChanges());
            } else {
                taskInputs = instantiator.newInstance(RebuildIncrementalTaskInputs.class, task);
            }
            return taskInputs;
        }

        private boolean canPerformIncrementalBuild() {
            return collectChangedMessages(null, getStates().getRebuildChanges());
        }

        @Override
        public TaskCacheKey calculateCacheKey() {
            // Ensure that states are created
            getStates();
            return history.getCurrentExecution().calculateCacheKey();
        }

        @Override
        public FileCollection getOutputFiles(String propertyName) {
            TaskExecution lastExecution = history.getPreviousExecution();
            if (lastExecution != null) {
                Map<String, FileCollectionSnapshot> lastSnapshots = lastExecution.getOutputFilesSnapshot();
                if (lastSnapshots != null) {
                    FileCollectionSnapshot propertySnapshots = lastSnapshots.get(propertyName);
                    if (propertySnapshots != null) {
                        return fileCollectionFactory.fixed("Task " + task.getPath() + " " + propertyName + " outputs", propertySnapshots.getFiles());
                    }
                }
            }
            return fileCollectionFactory.empty("Task " + task.getPath() + " " + propertyName + " outputs");
        }

        public TaskExecutionHistory getExecutionHistory() {
            return this;
        }

        public void beforeTask() {
        }

        public void afterTask() {
            if (upToDate) {
                return;
            }

            if (taskInputs != null) {
                getStates().newInputs(taskInputs.getDiscoveredInputs());
            }
            getStates().getAllTaskChanges().snapshotAfterTask();
            history.update();
        }

        public void finished() {
        }

        private TaskUpToDateState getStates() {
            if (states == null) {
                // Calculate initial state - note this is potentially expensive
                states = new TaskUpToDateState(task, history, outputFilesSnapshotter, inputFilesSnapshotter, discoveredInputsSnapshotter, fileCollectionFactory, classLoaderHierarchyHasher);
            }
            return states;
        }
    }

}
