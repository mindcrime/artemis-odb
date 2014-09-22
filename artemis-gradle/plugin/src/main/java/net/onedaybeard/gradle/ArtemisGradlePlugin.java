package net.onedaybeard.gradle;

import org.gradle.api.Project;
import org.gradle.api.Plugin;

/**
 * @author Daan van Yperen
 */
public class ArtemisGradlePlugin  implements Plugin<Project> {

	@Override
	public void apply(Project target) {
		System.out.println("Gradle plugins are cool");
		target.getTasks().create("weavingTask", ArtemisWeavingTask.class);
	}

}