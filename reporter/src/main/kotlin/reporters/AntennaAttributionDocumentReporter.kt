/*
 * Copyright (C) 2020 Bosch.IO GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package org.ossreviewtoolkit.reporter.reporters

import java.io.File
import java.io.OutputStream
import java.net.URL
import java.net.URLClassLoader
import java.util.SortedSet

import org.eclipse.sw360.antenna.attribution.document.core.AttributionDocumentGeneratorImpl
import org.eclipse.sw360.antenna.attribution.document.core.DocumentValues
import org.eclipse.sw360.antenna.attribution.document.core.model.LicenseInfo

import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.LicenseFindings
import org.ossreviewtoolkit.model.OrtResult
import org.ossreviewtoolkit.model.config.PathExclude
import org.ossreviewtoolkit.model.utils.collectLicenseFindings
import org.ossreviewtoolkit.model.utils.getDetectedLicensesForId
import org.ossreviewtoolkit.reporter.LicenseTextProvider
import org.ossreviewtoolkit.reporter.Reporter
import org.ossreviewtoolkit.reporter.ReporterInput
import org.ossreviewtoolkit.reporter.utils.AttributionDocumentPdfModel
import org.ossreviewtoolkit.spdx.SpdxLicense
import org.ossreviewtoolkit.utils.safeDeleteRecursively
import org.ossreviewtoolkit.utils.toHexString

private const val DEFAULT_TEMPLATE_ID = "basic-pdf-template"
private const val TEMPLATE_ID = "template.id"
private const val TEMPLATE_PATH = "template.path"

class AntennaAttributionDocumentReporter : Reporter {
    override val reporterName = "AntennaAttributionDocument"
    override val defaultFilename = "attribution-document.pdf"

    override fun generateReport(outputStream: OutputStream, input: ReporterInput, options: Map<String, String>) {
        val licenseFindings = input.ortResult.collectLicenseFindings(
            input.packageConfigurationProvider,
            omitExcluded = true
        )

        val artifacts = input.ortResult.getPackages().map { (pkg, _) ->
            val licenses = collectLicenses(pkg.id, input.ortResult)
            AttributionDocumentPdfModel(
                purl = pkg.purl,
                binaryFilename = pkg.binaryArtifact.takeUnless { it.url.isEmpty() }?.let { File(it.url).name },
                declaredLicenses = licenses.map { createLicenseInfo(it, input.licenseTextProvider) },
                copyrightStatements = createCopyrightStatement(pkg.id, licenses, licenseFindings)
            )
        }.toList()

        val rootProject = input.ortResult.getProjects().singleOrNull()

        // TODO: Add support for multiple projects.
        requireNotNull(rootProject) {
            "The $reporterName currently only supports ORT results with a single project."
        }

        val projectCopyright = createCopyrightStatement(
            rootProject.id,
            collectLicenses(rootProject.id, input.ortResult),
            licenseFindings
        )

        // Use the default template unless a custom template is provided via the options.
        var templateId = DEFAULT_TEMPLATE_ID
        options[TEMPLATE_ID]?.let { id ->
            options[TEMPLATE_PATH]?.let { path ->
                templateId = id
                addTemplateToClasspath(File(path).toURI().toURL())
            }
        }

        val workingDir = createTempDir()
        val documentFile = AttributionDocumentGeneratorImpl(
            defaultFilename,
            workingDir,
            templateId,
            DocumentValues(rootProject.id.name, rootProject.id.version, projectCopyright)
        ).generate(artifacts)

        if (documentFile.isFile) {
            documentFile.inputStream().use {
                it.copyTo(outputStream)
            }
        }

        workingDir.safeDeleteRecursively()
    }

    private fun createCopyrightStatement(
        id: Identifier,
        licenses: SortedSet<String>,
        licenseFindings: Map<Identifier, Map<LicenseFindings, List<PathExclude>>>
    ) =
        licenseFindings.getOrDefault(id, emptyMap())
            .filter { licenses.contains(it.key.license) }
            .flatMap { it.key.copyrights }
            .joinToString("\n") { it.statement }

    private fun addTemplateToClasspath(url: URL) {
        val addURL = URLClassLoader::class.java.getDeclaredMethod("addURL", URL::class.java)
        addURL.isAccessible = true
        addURL.invoke(ClassLoader.getSystemClassLoader(), url)
    }

    private fun collectLicenses(id: Identifier, ortResult: OrtResult): SortedSet<String> {
        val concludedLicense = ortResult.getConcludedLicensesForId(id)?.licenses() ?: emptyList()
        val declaredLicense = ortResult.getDeclaredLicensesForId(id)
        val detectedLicense = ortResult.getDetectedLicensesForId(id)

        return if (concludedLicense.isNotEmpty()) {
            concludedLicense.toSortedSet()
        } else {
            (declaredLicense + detectedLicense).toSortedSet()
        }
    }

    private fun createLicenseInfo(
        licenseId: String,
        licenseTextProvider: LicenseTextProvider
    ) =
        LicenseInfo(
            // Generate a key that is used as the license anchor in the PDF. A valid key consists of numbers and letters
            // only, any special characters are invalid.
            licenseId.toByteArray().toHexString(),
            // Replace tabs with spaces to work around an issue where the chosen font does not provide a (horizontal)
            // tab character, which otherwise causes something like:
            //
            //     IllegalArgumentException: U+0009 ('controlHT') is not available in this font Times-Roman encoding:
            //     WinAnsiEncoding
            licenseTextProvider.getLicenseText(licenseId)?.replace("\t", "    ") ?: "No license text found.",
            licenseId,
            SpdxLicense.forId(licenseId)?.fullName ?: licenseId
        )
}