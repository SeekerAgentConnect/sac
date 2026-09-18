package io.github.brrenat.seekervault.designsystem.preview

/**
 * Gives a preview the exact design-guide path used by SEE-113.
 *
 * Roborazzi writes the preview to `<component>/<slug(variant)>.png`. Keeping this annotation next
 * to the preview makes a renamed or unpaired specimen fail before it silently creates an unrelated
 * golden.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class DesignRef(val component: String, val variant: String)
