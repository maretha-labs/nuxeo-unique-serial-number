# Asset layouts for Nuxeo Web UI

The `2023` branch provides the Polymer create, edit, import, view and metadata layouts for the Asset document type. The forms send the generation flag and date with the Asset save and display the matching string and long returned by Nuxeo. [Asset layouts](src/main/resources/web/nuxeo.war/ui/document/asset/)

Install the LTS 2023 add-on Marketplace package alongside the matching Nuxeo Web UI package. The web resource bundle registers the layouts and messages, and the HTML files deploy at `/ui/document/asset/`. [Build and install](../README.md#build-and-install)

The Maretha Angular Web UI layouts target Nuxeo LTS 2025. [LTS 2025 UI module](https://github.com/maretha-labs/nuxeo-unique-serial-number/tree/2025/nuxeo-unique-serial-number-web-ui)
