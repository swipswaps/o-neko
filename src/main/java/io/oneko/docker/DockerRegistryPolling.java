package io.oneko.docker;

import static io.oneko.deployable.DeploymentBehaviour.*;
import static io.oneko.kubernetes.deployments.DesiredState.*;
import static io.oneko.util.DurationUtils.*;
import static org.apache.commons.lang3.time.DurationFormatUtils.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.google.common.collect.Sets;

import io.oneko.docker.event.NewProjectVersionFoundEvent;
import io.oneko.docker.event.ObsoleteProjectVersionRemovedEvent;
import io.oneko.docker.v2.DockerRegistryClientFactory;
import io.oneko.docker.v2.model.manifest.Manifest;
import io.oneko.domain.Identifiable;
import io.oneko.event.CurrentEventTrigger;
import io.oneko.event.Event;
import io.oneko.event.EventDispatcher;
import io.oneko.event.EventTrigger;
import io.oneko.event.ScheduledTask;
import io.oneko.kubernetes.DeploymentManager;
import io.oneko.project.ProjectRepository;
import io.oneko.project.ProjectVersion;
import io.oneko.project.ReadableProject;
import io.oneko.project.ReadableProjectVersion;
import io.oneko.project.WritableProject;
import io.oneko.project.WritableProjectVersion;
import io.oneko.util.ExpiringBucket;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
class DockerRegistryPolling {

	@Data
	private static class VersionWithDockerManifest {
		private final WritableProjectVersion version;
		private final Manifest manifest;
	}

	private final ProjectRepository projectRepository;
	private final DockerRegistryClientFactory dockerRegistryClientFactory;
	private final DeploymentManager deploymentManager;
	private final EventDispatcher eventDispatcher;
	private final EventTrigger asTrigger;
	private final ExpiringBucket<UUID> failedManifestRequests = new ExpiringBucket<UUID>(Duration.ofMinutes(5)).concurrent();
	private final CurrentEventTrigger currentEventTrigger;

	DockerRegistryPolling(ProjectRepository projectRepository,
												DockerRegistryClientFactory dockerRegistryClientFactory,
												DeploymentManager deploymentManager,
												EventDispatcher eventDispatcher,
												CurrentEventTrigger currentEventTrigger) {
		this.projectRepository = projectRepository;
		this.dockerRegistryClientFactory = dockerRegistryClientFactory;
		this.deploymentManager = deploymentManager;
		this.eventDispatcher = eventDispatcher;
		this.currentEventTrigger = currentEventTrigger;
		this.asTrigger = new ScheduledTask("Docker Registry Polling");
	}

	@Scheduled(fixedDelay = 20000, initialDelay = 10000)
	protected void checkDockerForNewImages() {
		try (var ignored = currentEventTrigger.forTryBlock(this.asTrigger)) {
			final Instant start = Instant.now();
			log.trace("Starting polling job");

			final List<WritableProject> projects = projectRepository.getAll().stream()
					.map(ReadableProject::writable)
					.collect(Collectors.toList());

			checkDockerForNewImages(projects);
			final Instant stop = Instant.now();

			final Duration duration = Duration.between(start, stop);
			final Duration warnIfLongerThanThis = Duration.ofMinutes(5);
			if (isLongerThan(duration, warnIfLongerThanThis)) {
				log.warn("Checking for new images took longer than {} (took {}) [HH:mm:ss.SSS]", formatDurationHMS(warnIfLongerThanThis.toMillis()), formatDurationHMS(duration.toMillis()));
			}

			log.trace("Finished polling job (took {} [HH:mm:ss.SSS])", formatDurationHMS(duration.toMillis()));
		}
	}

	@Scheduled(fixedDelay = 1000 * 60 * 60 * 2, initialDelay = 60000) // Every 2 hours
	protected void updateDatesForAllImagesAndAllTags() {
		final Instant start = Instant.now();
		log.trace("Updating dates for all projects and all versions");
		projectRepository.getAll()
				.stream()
				.map(ReadableProject::writable)
				.forEach(project -> fetchAndUpdateDatesForVersionsOfProject(project, project.getVersions()));

		final Instant stop = Instant.now();
		final Duration duration = Duration.between(start, stop);
		final Duration warnIfLongerThanThis = Duration.ofMinutes(10);
		if (isLongerThan(duration, warnIfLongerThanThis)) {
			log.warn("Updating dates for all projects and all versions took longer than {} (took {}) [HH:mm:ss.SSS]", formatDurationHMS(warnIfLongerThanThis.toMillis()), formatDurationHMS(duration.toMillis()));
		}

		log.trace("Finished updating dates for all projects (took {} [HH:mm:ss.SSS])", formatDurationHMS(duration.toMillis()));
	}

	/**
	 * Iterates through all project versions checking for updates. Triggers re-deployments if necessary.
	 */
	private void checkDockerForNewImages(List<WritableProject> allProjects) {
		for (var project : allProjects) {
			try {
				project = this.updateProjectVersions(project);
			} catch (Exception e) {
				log.error("Encountered an exception while checking new project versions of {}", project.getName(), e);
			}

			try {
				project = this.fetchAndUpdateMissingDatesForImages(project);
			} catch (Exception e) {
				log.error("Encountered an exception while fetching the dates of the images {} {}", project.getName(), e);
			}

			try {
				checkForNewImages(project);
			} catch (Exception e) {
				log.error("Error on checking for new images for projects.", e);
			}
		}
	}


	private WritableProject fetchAndUpdateMissingDatesForImages(WritableProject project) {
		final List<WritableProjectVersion> versionsWithoutDate = project.getVersions().stream()
				.filter(version -> version.getImageUpdatedDate() == null)
				.filter(version -> !failedManifestRequests.contains(version.getUuid()))
				.collect(Collectors.toList());

		return fetchAndUpdateDatesForVersionsOfProject(project, versionsWithoutDate);
	}

	private WritableProject fetchAndUpdateDatesForVersionsOfProject(WritableProject project, List<WritableProjectVersion> versions) {
		if (versions.isEmpty()) {
			return project;
		}

		log.trace("Updating dates for {} versions of project {}", versions.size(), project.getName());
		final var versionWithDockerManifestList = versions.parallelStream()
				.map(version -> getManifestWithContext(project, version))
				.collect(Collectors.toList());

		for (var versionWithDockerManifest : versionWithDockerManifestList) {
			final var manifest = versionWithDockerManifest.getManifest();
			final var version = versionWithDockerManifest.getVersion();

			if (manifest == null || manifest.getImageUpdatedDate().isEmpty()) {
				log.trace("Failed to get Manifest for version {} of project {}.", version.getName(), project.getName());
				failedManifestRequests.add(version.getUuid());
				continue;
			}

			version.setImageUpdatedDate(manifest.getImageUpdatedDate().orElse(null));
			log.trace("Setting date for version {} of project {} to {}.", version.getName(), project.getName(), version.getImageUpdatedDate());
		}

		return projectRepository.add(project).writable();
	}

	private VersionWithDockerManifest getManifestWithContext(WritableProject project, WritableProjectVersion version) {
		try {
			return dockerRegistryClientFactory.getDockerRegistryClient(project)
					.map(client -> new VersionWithDockerManifest(version, client.getManifest(version)))
					.orElseGet(() -> new VersionWithDockerManifest(version, null));
		} catch (Exception e) {
			log.error("Failed to retrieve manifest", e);
			return new VersionWithDockerManifest(version, null);
		}
	}

	private WritableProject updateProjectVersions(WritableProject project) {
		log.trace("Checking for new versions of project {}", project.getName());
		final var dockerClient = dockerRegistryClientFactory.getDockerRegistryClient(project)
				.orElseThrow(() -> new RuntimeException(String.format("Project %s has no docker registry or an error occurred instantiating the docker registry client", project.getName())));

		final var tags = Objects.requireNonNullElse(dockerClient.getAllTags(project), Collections.<String>emptyList());
		log.trace("Found {} tags for project {}", tags.size(), project.getName());

		return manageAvailableVersions(project, tags);
	}

	private WritableProject manageAvailableVersions(WritableProject project, List<String> tags) {
		final var versions = project.getVersions().stream()
				.map(ProjectVersion::getName)
				.collect(Collectors.toSet());

		final var newVersions = Sets.difference(Sets.newHashSet(tags), versions);
		final var removedVersions = Sets.difference(versions, Sets.newHashSet(tags));
		final var resultingEvents = new ArrayList<Event>();

		if (!newVersions.isEmpty()) {
			if (newVersions.size() == 1) {
				log.info("Found new version {} for project {}", newVersions.toArray()[0], project.getName());
			} else {
				log.info("Found {} new versions for project {}, {}", newVersions.size(), project.getName(), newVersions);
			}
		}

		newVersions.forEach(version -> {
			WritableProjectVersion projectVersion = project.createVersion(version);
			resultingEvents.add(new NewProjectVersionFoundEvent(projectVersion));
		});

		removedVersions.forEach(version -> {
			WritableProjectVersion projectVersion = project.removeVersion(version);
			log.info("Found an obsolete version {} [{}] for project {}", version, projectVersion.getId(), project.getName());
			resultingEvents.add(new ObsoleteProjectVersionRemovedEvent(projectVersion));
		});

		if (!newVersions.isEmpty() || !removedVersions.isEmpty()) {
			ReadableProject savedProject = projectRepository.add(project);
			resultingEvents.forEach(eventDispatcher::dispatch);
			return savedProject.writable();
		}

		return project;
	}

	/**
	 * Checks for new images in this projects that might require this image.
	 */
	private void checkForNewImages(WritableProject project) {
		project.getVersions().forEach(version -> {

			if (version.getDesiredState() == NotDeployed ||
					version.getDesiredState() == Deployed && version.getDeploymentBehaviour() != automatically) {
				//nothing to do here...
				return;
			}

			final var manifestWithContext = getManifestWithContext(project, version);
			if (manifestWithContext.getManifest() == null) {
				log.trace("Failed to get Manifest for version {} of project {}.", version.getName(), project.getName());
				failedManifestRequests.add(version.getUuid());
				return;
			}

			if (!StringUtils.isBlank(manifestWithContext.getManifest().getDockerContentDigest())) {
				this.redeployAllByManifest(manifestWithContext.getManifest(), version);
			}
		});
	}

	/**
	 * Tries to redeploy a project version
	 */
	private List<Identifiable> redeployAllByManifest(Manifest manifest, WritableProjectVersion version) {
		List<Identifiable> depending = new ArrayList<>();

		String digest = manifest.getDockerContentDigest();
		if (!StringUtils.equals(version.getDockerContentDigest(), digest)) {
			log.info("Found a new image '{}' for project '{}' version '{}'", digest, version.getProject().getName(), version.getName());
			version.setDockerContentDigest(digest);
			version.setImageUpdatedDate(manifest.getImageUpdatedDate().orElse(null));
			this.redeployAndSaveVersion(version).ifPresent(depending::add);
		}

		return depending;
	}

	private Optional<ReadableProjectVersion> redeployAndSaveVersion(WritableProjectVersion version) {
		if (version.getDesiredState() == Deployed && version.getDeploymentBehaviour() == automatically) {
			return Optional.of(deploymentManager.deploy(version));
		}

		return Optional.empty();
	}
}
