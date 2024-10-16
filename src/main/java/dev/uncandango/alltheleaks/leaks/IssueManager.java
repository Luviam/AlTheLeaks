package dev.uncandango.alltheleaks.leaks;

import com.google.common.collect.Sets;
import dev.uncandango.alltheleaks.AllTheLeaks;
import dev.uncandango.alltheleaks.annotation.AnnotationHelper;
import dev.uncandango.alltheleaks.annotation.Issue;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.LoadingModList;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.VersionRange;

import java.lang.annotation.ElementType;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

public class IssueManager {
	private static final List<String> constructors = new ArrayList<>();
	private static Set<String> mixinAllowed;

	public static Set<String> getAllowedMixins() {
		if (mixinAllowed == null) {
			mixinAllowed = Sets.newHashSet();
			@SuppressWarnings("UnstableApiUsage")
			var scanData = LoadingModList.get().getModFileById(AllTheLeaks.MOD_ID).getFile().getScanResult();
			var currentDist = FMLEnvironment.dist;
			scanData.getAnnotatedBy(Issue.class, ElementType.TYPE).forEach(annotation -> {
				String issueId = AnnotationHelper.getValue(annotation, "issueId");
				String modId = AnnotationHelper.getValue(annotation, "modId");
				Boolean solved = AnnotationHelper.getValue(annotation, "solved");
				List<String> extraModDep = AnnotationHelper.getValue(annotation, "extraModDep");
				List<String> extraModDepVersions = AnnotationHelper.getValue(annotation, "extraModDepVersions");
				if (currentDist.isDedicatedServer()) {
					Dist side = annotation.memberName().contains(".client.mods.") ? Dist.CLIENT : Dist.DEDICATED_SERVER;
					if (side.isClient()) {
						AllTheLeaks.LOGGER.info("Skipping issue {} from mod {} as it is client side only!", issueId, modId);
						return;
					}
				}
				if (solved) {
					AllTheLeaks.LOGGER.info("Skipping issue {} from mod {} as it has been solved by mod!", issueId, modId);
					return;
				}
				String versionRange = AnnotationHelper.getValue(annotation, "versionRange");
				List<String> mixins = AnnotationHelper.getValue(annotation, "mixins");
				var condition = generateCondition(modId, versionRange, annotation.memberName(), extraModDep, extraModDepVersions);
				if (condition.get() && mixins != null && !mixins.isEmpty()) {
					AllTheLeaks.LOGGER.info("Mixins added to allowed list: {}", mixins);
					mixinAllowed.addAll(mixins);
				}
			});
		}
		return mixinAllowed;
	}

	public static void initiateIssues() {
		constructors.forEach(ctor -> {
			try {
				var clazz = Class.forName(ctor);
				var ctorClazz = clazz.getDeclaredConstructors()[0];
				ctorClazz.newInstance();
			} catch (Throwable e) {
				AllTheLeaks.LOGGER.error("Failed to instantiate constructor.", e);
			}
		});


	}

	public static Supplier<Boolean> generateCondition(String modId, String versionRange, String annotatedClass, List<String> extraModDep, List<String> extraModDepVersions) {
		return () -> {
			var mod = LoadingModList.get().getModFileById(modId);
			if (mod != null) {
				try {
					var range = VersionRange.createFromVersionSpec(versionRange);
					var modVer = new DefaultArtifactVersion(mod.versionString());
					if (range.containsVersion(modVer)) {
						if (!extraModDep.isEmpty()) {
							for (int i = 0; i < extraModDep.size(); i++) {
								var dep = LoadingModList.get().getModFileById(extraModDep.get(i));
								if (dep == null) {
									AllTheLeaks.LOGGER.info("Extra dependency Mod {} is not present in the mod list", extraModDep.get(i));
									AllTheLeaks.LOGGER.info("Class {} will NOT be loaded as extra mod dependecy is not present.", annotatedClass);
									return false;
								}
								var rangeDepVer = VersionRange.createFromVersionSpec(extraModDepVersions.get(i));
								var depVer = new DefaultArtifactVersion(dep.versionString());
								if (rangeDepVer.containsVersion(depVer)) {
									AllTheLeaks.LOGGER.info("Extra dependecy Mod {} matches versions: {} in {}", extraModDep.get(i),dep.versionString(), extraModDepVersions.get(i));
								} else {
									AllTheLeaks.LOGGER.info("Extra dependecy Mod {} does NOT matches versions: {} in {}", extraModDep.get(i),dep.versionString(), extraModDepVersions.get(i));
									AllTheLeaks.LOGGER.info("Class {} will NOT be loaded as extra mod dependecy does not match.", annotatedClass);
									return false;
								}
							}
						}
						constructors.add(annotatedClass);
						AllTheLeaks.LOGGER.info("Class {} will be loaded as it matches versions: {} in {}", annotatedClass, modVer, range);
						return true;
					} else {
						AllTheLeaks.LOGGER.info("Class {} will NOT be loaded as mod {} does not match versions: {} in {}", annotatedClass, modId, modVer, range);
					}
				} catch (Exception e) {
					AllTheLeaks.LOGGER.error("Error while comparing versions and instantiating class", e);
				}
			} else {
				AllTheLeaks.LOGGER.info("Class {} will NOT be loaded as mod {} is not present", annotatedClass, modId);
			}
			return false;
		};
	}
}
