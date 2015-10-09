/*
 * Copyright 2015 Time Warner Cable, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.twcable.grabbit.client.batch

import com.twcable.grabbit.jcr.JcrUtil
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.apache.http.HttpEntity
import org.apache.http.HttpHost
import org.apache.http.HttpResponse
import org.apache.http.auth.AuthScope
import org.apache.http.auth.UsernamePasswordCredentials
import org.apache.http.client.AuthCache
import org.apache.http.client.CredentialsProvider
import org.apache.http.client.HttpClient
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.protocol.HttpClientContext
import org.apache.http.impl.auth.BasicScheme
import org.apache.http.impl.client.BasicAuthCache
import org.apache.http.impl.client.BasicCredentialsProvider
import org.apache.http.impl.client.DefaultHttpClient
import org.apache.sling.jcr.api.SlingRepository
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobExecutionListener
import org.springframework.batch.core.JobParameters
import org.apache.http.client.utils.URIBuilder

import javax.jcr.Session

/**
 * A JobExecutionListener that hooks into {@link JobExecutionListener#beforeJob(JobExecution)} to setup() the job and
 * {@link JobExecutionListener#afterJob(JobExecution)} to cleanup() after the job
 */
@Slf4j
@CompileStatic
@SuppressWarnings("GrMethodMayBeStatic")
class ClientBatchJobExecutionListener implements JobExecutionListener {

    /**
     * {@link SlingRepository} is managed by Spring-OSGi
     */
    private SlingRepository slingRepository


    void setSlingRepository(SlingRepository slingRepository) {
        this.slingRepository = slingRepository
    }

    // **********************************************************************
    // METHODS BELOW ARE USED TO SETUP THE CLIENT JOB.
    // THE METHOD beforeJob() IS CALLED AFTER THE CLIENT JOB STARTS AND BEFORE
    // ANY OF THE STEPS ARE EXECUTED
    // **********************************************************************

    /**
     * Callback before a job executes.
     * @param jobExecution the current {@link JobExecution}
     */
    @Override
    void beforeJob(JobExecution jobExecution) {
        setup(jobExecution.jobParameters)
        log.info "Starting job : ${jobExecution}\n\n"
    }

    /**
     * Method that makes request to provided server using {@link JobParameters}
     * Creates a {@link Session} with using the {@link ClientBatchJobExecutionListener#slingRepository}
     * Stores the InputStream and Session on Current Thread's {@link ThreadLocal}
     * @param jobParameters
     */
    private void setup(final JobParameters jobParameters) {
        log.debug "SlingRepository : ${slingRepository}"
        HttpResponse response = doRequest(jobParameters)
        HttpEntity responseEntity = response.entity
        final InputStream inputStream = responseEntity.content

        final clientUsername = jobParameters.getString(ClientBatchJob.CLIENT_USERNAME)
        final Session session = JcrUtil.getSession(slingRepository, clientUsername)

        ClientBatchJobContext clientBatchJobContext = new ClientBatchJobContext(inputStream, session)
        ClientBatchJobContext.THREAD_LOCAL.set(clientBatchJobContext)

    }

    /**
     * Gets a Http Get connection for the provided authentication information
     * @param username
     * @param password
     * @return a {@link DefaultHttpClient} instance
     */
    private DefaultHttpClient getHttpClient(final String username, final String password) {
        DefaultHttpClient client = new DefaultHttpClient()

        CredentialsProvider credentialsProvider = new BasicCredentialsProvider()
        credentialsProvider.setCredentials(
            new AuthScope(AuthScope.ANY_HOST, AuthScope.ANY_PORT),
            new UsernamePasswordCredentials(username, password)
        )
        client.setCredentialsProvider(credentialsProvider)
        client
    }

    /**
     * Gets a new HttpClientContext for the given HttpRequest. The ClientContext sets up preemptive
     * authentication using BasicAuthCache
     * @param get the HttpGet request
     * @return the HttpClientContext for given HttpRequest
     */
    private HttpClientContext getHttpClientContext(HttpGet get) {
        // Setup preemptive Authentication
        // Create AuthCache instance
        AuthCache authCache = new BasicAuthCache()
        HttpHost host = new HttpHost(get.URI.host, get.URI.port, get.URI.scheme)
        authCache.put(host, new BasicScheme())
        // Add AuthCache to the execution context
        HttpClientContext context = HttpClientContext.create()
        context.setAuthCache(authCache)
        return context
    }

    /**
     * Makes a Http Get request to the grab path and returns the response
     * @param jobParameters that contain information like the path, host, port, etc.
     * @return the httpResponse
     */
    private HttpResponse doRequest(JobParameters jobParameters) {
        final String path = jobParameters.getString(ClientBatchJob.PATH)
        final String excludePathParam = jobParameters.getString(ClientBatchJob.EXCLUDE_PATHS)
        final excludePaths = (excludePathParam != null && !excludePathParam.isEmpty() ? excludePathParam.split(/\*/) : Collections.EMPTY_LIST) as Collection<String>
        final String host = jobParameters.getString(ClientBatchJob.HOST)
        final String port = jobParameters.getString(ClientBatchJob.PORT)
        final String username = jobParameters.getString(ClientBatchJob.SERVER_USERNAME)
        final String password = jobParameters.getString(ClientBatchJob.SERVER_PASSWORD)
        final String contentAfterDate = jobParameters.getString(ClientBatchJob.CONTENT_AFTER_DATE) ?: ""

        final String encodedContentAfterDate = URLEncoder.encode(contentAfterDate, 'utf-8')
        final String encodedPath = URLEncoder.encode(path, 'utf-8')

        URIBuilder uriBuilder = new URIBuilder(scheme: "http", host: host, port: port as Integer, path: "/grabbit/job")
        uriBuilder.addParameter("path", encodedPath)
        uriBuilder.addParameter("after", encodedContentAfterDate)
        for(String excludePath : excludePaths) {
            uriBuilder.addParameter("excludePath", URLEncoder.encode(excludePath, 'UTF-8'))
        }

        //create the get request
        HttpGet get = new HttpGet(uriBuilder.build())
        HttpClient client = getHttpClient(username, password)
        HttpClientContext context = getHttpClientContext(get)
        HttpResponse response = client.execute(get, context)
        response
    }

    // **********************************************************************
    // METHODS BELOW ARE USED TO CLEANUP AFTER CLIENT JOB IS COMPLETE.
    // THE METHOD afterJob() IS CALLED AFTER THE CLIENT JOB STEPS ARE COMPLETE
    // AND BEFORE THE JOB ACTUALLY TERMINATES
    // **********************************************************************

    /**
     * Callback after completion of a job.
     * @param jobExecution the current {@link JobExecution}
     */
    @Override
    void afterJob(JobExecution jobExecution) {
        log.info "Cleanup : ${jobExecution} . Job Complete. Clearing THREAD_LOCAL. Releasing inputStream, session"
        cleanup()
        final long timeTaken = jobExecution.endTime.time - jobExecution.startTime.time
        log.info "Grab from ${jobExecution.jobParameters.getString(ClientBatchJob.HOST)} " +
            "for Current Path ${jobExecution.jobParameters.getString(ClientBatchJob.PATH)} took : ${timeTaken} milliseconds\n\n"
    }

    /**
     * Cleans up the current Thread's {@link ThreadLocal}
     */
    private void cleanup() {
        ClientBatchJobContext clientBatchJobContext = ClientBatchJobContext.THREAD_LOCAL.get()
        try {
            clientBatchJobContext.inputStream.close()
        }
        catch (Exception ignore) {
            // just doing cleanup
        }
        try {
            clientBatchJobContext.session.logout()
        }
        catch (Exception ignore) {
            //just doing cleanup
        }
        ClientBatchJobContext.THREAD_LOCAL.remove()
    }

}
