# APS Knowledge Enrichment Delegate
Calling Knowledge Enrichment from Alfresco Process Services using a Java delgate.

### How it Works:
This project contains multiple API calls to Hyland's Knowledge Enrichment product, including the Authentication API call and the Context API calls necessary for performing a job. The Authentication and Context API calls are seperated to provide more flexibility at implementation.
The delegate contains three classes, outline here:
- **OAuthApiCallDelegate**: Calls the auth API and receives a Bearer Token.
- **ContextEnrichmentApiDelegate**: Processes an image (jpeg) with "image-description" action to get a summarization of the image.
- **ContextEnrichmentMetadata**: Processes a PDF to get with "text-metadata-generation" action to get keywords based on document content. Each metadata value is saved back to the process in individual variables; see lines 224-230 in this class for the variable names.

**Setting Up Your Project**
1. Be sure to change the client_id and client_secret values in the ```config.properties``` file inside the Java package to connect to your own CIC agent.
2. This project assumes you're importing or uploading a jpeg image as a base64 variabled named ```imageBase64``` and either creating or uploading a PDF to your process as a base64 variable named ```objPDF```.

**Building your Process**
1. First, a Service task should call the ```com.example.aps.delegate.OAuthApiCallDelegate``` class to get a bearer token, which is saved to a process variable called: ```accessToken```.
The next steps can be in any order:
2. Provide a service task that processes the summary on the image variable [imageBase64] calling the class: ```com.example.aps.delegate.ContextEnrichmentApiDelegate```.
3. Provide a service task that processes the metadata extraction on the PDF variable [objPDF] calling the class: ```com.example.aps.delegate.ContextEnrichmentMetadata```.
   - To edit the pre-configured JSON model [kSimilarMetadata] being sent to the context API for this call you'll need to edit the Java class (for now). 
   - Review the API here for further guidance: [Process Content Async](https://hyland.github.io/ContentIntelligence-Docs/KnowledgeEnrichment/Reference/Context%20API/Endpoints/process-content-asynchronously)

