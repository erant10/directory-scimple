/*
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
 
* http://www.apache.org/licenses/LICENSE-2.0

* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*/

package org.apache.directory.scim.server.rest;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import jakarta.enterprise.inject.spi.CDI;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.*;
import jakarta.ws.rs.core.Response.ResponseBuilder;
import jakarta.ws.rs.core.Response.Status;
import jakarta.ws.rs.core.Response.Status.Family;

import org.apache.directory.scim.protocol.exception.ScimException;
import org.apache.directory.scim.server.exception.*;
import org.apache.directory.scim.core.repository.RepositoryRegistry;
import org.apache.directory.scim.core.repository.Repository;
import org.apache.directory.scim.core.schema.SchemaRegistry;
import org.apache.directory.scim.spec.exception.ResourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.directory.scim.core.repository.UpdateRequest;
import org.apache.directory.scim.core.repository.annotations.ScimProcessingExtension;
import org.apache.directory.scim.core.repository.extensions.AttributeFilterExtension;
import org.apache.directory.scim.core.repository.extensions.ProcessingExtension;
import org.apache.directory.scim.spec.filter.attribute.ScimRequestContext;
import org.apache.directory.scim.core.repository.extensions.ClientFilterException;
import org.apache.directory.scim.protocol.adapter.FilterWrapper;
import org.apache.directory.scim.protocol.BaseResourceTypeResource;
import org.apache.directory.scim.spec.filter.attribute.AttributeReference;
import org.apache.directory.scim.spec.filter.attribute.AttributeReferenceListWrapper;
import org.apache.directory.scim.protocol.data.ErrorResponse;
import org.apache.directory.scim.protocol.data.ListResponse;
import org.apache.directory.scim.protocol.data.PatchRequest;
import org.apache.directory.scim.protocol.data.SearchRequest;
import org.apache.directory.scim.spec.filter.FilterResponse;
import org.apache.directory.scim.spec.filter.Filter;
import org.apache.directory.scim.spec.filter.PageRequest;
import org.apache.directory.scim.spec.filter.SortOrder;
import org.apache.directory.scim.spec.filter.SortRequest;
import org.apache.directory.scim.spec.resources.ScimResource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class BaseResourceTypeResourceImpl<T extends ScimResource> implements BaseResourceTypeResource<T> {

  private static final Logger LOG = LoggerFactory.getLogger(BaseResourceTypeResourceImpl.class);

  private final SchemaRegistry schemaRegistry;

  private final RepositoryRegistry repositoryRegistry;

  private final  AttributeUtil attributeUtil;

  private final  EtagGenerator etagGenerator;

  private final Class<T> resourceClass;

  // TODO: Field injection of UriInfo, Request should work with all implementations
  // CDI can be used directly in Jakarta WS 4
  @Context
  UriInfo uriInfo;

  @Context
  Request request;

  public BaseResourceTypeResourceImpl(SchemaRegistry schemaRegistry, RepositoryRegistry repositoryRegistry, EtagGenerator etagGenerator, Class<T> resourceClass) {
    this.schemaRegistry = schemaRegistry;
    this.repositoryRegistry = repositoryRegistry;
    this.etagGenerator = etagGenerator;
    this.resourceClass = resourceClass;
    this.attributeUtil = new AttributeUtil(schemaRegistry);
  }

  public Repository<T> getRepository() {
    return repositoryRegistry.getRepository(resourceClass);
  }

  Repository<T> getRepositoryInternal() throws ScimException {
    Repository<T> repository = getRepository();
    if (repository == null) {
      throw new ScimException(Status.NOT_IMPLEMENTED, "Provider not defined");
    }
    return repository;
  }

  @Override
  public Response getById(String id, AttributeReferenceListWrapper attributes, AttributeReferenceListWrapper excludedAttributes) throws ScimException, ResourceException {
    if (uriInfo.getQueryParameters().getFirst("filter") != null) {
      return Response.status(Status.FORBIDDEN).build();
    }

    Repository<T> repository = getRepositoryInternal();

    T resource = null;
    try {
      resource = repository.get(id);
    } catch (UnableToRetrieveResourceException e2) {
      Status status = Status.fromStatusCode(e2.getStatus());
      if (status.getFamily().equals(Family.SERVER_ERROR)) {
        throw e2;
      }
    }

    if (resource != null) {
      EntityTag backingETag = requireEtag(resource);
      ResponseBuilder evaluatePreconditionsResponse = request.evaluatePreconditions(backingETag);

      if (evaluatePreconditionsResponse != null) {
        return Response.status(Status.NOT_MODIFIED).build();
      }
    }

    Set<AttributeReference> attributeReferences = AttributeReferenceListWrapper.getAttributeReferences(attributes);
    Set<AttributeReference> excludedAttributeReferences = AttributeReferenceListWrapper.getAttributeReferences(excludedAttributes);
    validateAttributes(attributeReferences, excludedAttributeReferences);

    if (resource == null) {
      throw notFoundException(id);
    }

    EntityTag etag = requireEtag(resource);

    // Process Attributes
    resource = processFilterAttributeExtensions(repository, resource, attributeReferences, excludedAttributeReferences);
    resource = attributesForDisplayThrowOnError(resource, attributeReferences, excludedAttributeReferences);
    return Response.ok()
                   .entity(resource)
                   .location(buildLocationTag(resource))
                   .tag(etag)
                   .build();
  }

  @Override
  public Response query(AttributeReferenceListWrapper attributes, AttributeReferenceListWrapper excludedAttributes, FilterWrapper filter, AttributeReference sortBy, SortOrder sortOrder, Integer startIndex, Integer count) throws ScimException, ResourceException {
    SearchRequest searchRequest = new SearchRequest();
    searchRequest.setAttributes(AttributeReferenceListWrapper.getAttributeReferences(attributes));
    searchRequest.setExcludedAttributes(AttributeReferenceListWrapper.getAttributeReferences(excludedAttributes));

    if (filter != null) {
      searchRequest.setFilter(filter.getFilter());
    }
    else {
      searchRequest.setFilter(null);
    }
    
    searchRequest.setSortBy(sortBy);
    searchRequest.setSortOrder(sortOrder);
    searchRequest.setStartIndex(startIndex);
    searchRequest.setCount(count);

    return find(searchRequest);
  }

  @Override
  public Response create(T resource, AttributeReferenceListWrapper attributes, AttributeReferenceListWrapper excludedAttributes) throws ScimException, ResourceException {
    Repository<T> repository = getRepositoryInternal();

    Set<AttributeReference> attributeReferences = AttributeReferenceListWrapper.getAttributeReferences(attributes);
    Set<AttributeReference> excludedAttributeReferences = AttributeReferenceListWrapper.getAttributeReferences(excludedAttributes);
    validateAttributes(attributeReferences, excludedAttributeReferences);

    T created = repository.create(resource);

    EntityTag etag = etag(created);

    // Process Attributes
    created = processFilterAttributeExtensions(repository, created, attributeReferences, excludedAttributeReferences);

    try {
      created = attributesForDisplay(created, attributeReferences, excludedAttributeReferences);
    } catch (AttributeException e) {
        log.debug("Exception thrown while processing attributes", e);
        return Response.status(Status.CREATED)
                .location(buildLocationTag(created))
                .tag(etag)
                .build();
    }

    return Response.status(Status.CREATED)
                   .location(buildLocationTag(created))
                   .tag(etag)
                   .entity(created)
                   .build();
  }

  @Override
  public Response find(SearchRequest request) throws ScimException, ResourceException {
    Repository<T> repository = getRepositoryInternal();

    Set<AttributeReference> attributeReferences = Optional.ofNullable(request.getAttributes())
                                                          .orElse(Collections.emptySet());
    Set<AttributeReference> excludedAttributeReferences = Optional.ofNullable(request.getExcludedAttributes())
                                                                  .orElse(Collections.emptySet());
    validateAttributes(attributeReferences, excludedAttributeReferences);

    Filter filter = request.getFilter();
    PageRequest pageRequest = request.getPageRequest();
    SortRequest sortRequest = request.getSortRequest();

    ListResponse<T> listResponse = new ListResponse<>();

    FilterResponse<T> filterResp = repository.find(filter, pageRequest, sortRequest);

    // If no resources are found, we should still return a ListResponse with
    // the totalResults set to 0;
    // (https://tools.ietf.org/html/rfc7644#section-3.4.2)
    if (filterResp == null || filterResp.getResources() == null || filterResp.getResources()
                                                                             .isEmpty()) {
      listResponse.setTotalResults(0);
    } else {
      log.debug("Find returned " + filterResp.getResources()
                                            .size());
      listResponse.setItemsPerPage(filterResp.getResources()
                                             .size());
      int startIndex = Optional.ofNullable(filterResp.getPageRequest().getStartIndex()).orElse(1);
      listResponse.setStartIndex(startIndex);
      listResponse.setTotalResults(filterResp.getTotalResults());

      List<T> results = new ArrayList<>();

      for (T resource : filterResp.getResources()) {
        requireEtag(resource);

        // Process Attributes
        resource = processFilterAttributeExtensions(repository, resource, attributeReferences, excludedAttributeReferences);
        resource = attributesForDisplayThrowOnError(resource, attributeReferences, excludedAttributeReferences);
        results.add(resource);
      }

      listResponse.setResources(results);
    }

    return Response.ok()
                   .entity(listResponse)
                   .build();
  }

  @Override
  public Response update(T resource, String id, AttributeReferenceListWrapper attributes, AttributeReferenceListWrapper excludedAttributes) throws ScimException, ResourceException {
    return update(id, attributes, excludedAttributes, (stored) ->
      new UpdateRequest<>(id, stored, resource, schemaRegistry));
  }

  @Override
  public Response patch(PatchRequest patchRequest, String id, AttributeReferenceListWrapper attributes, AttributeReferenceListWrapper excludedAttributes) throws ScimException, ResourceException {

    return update(id, attributes, excludedAttributes, (stored) ->
      new UpdateRequest<>(id, stored, patchRequest.getPatchOperationList(), schemaRegistry));
  }

  @Override
  public Response delete(String id) throws ScimException, ResourceException {
      Repository<T> repository = getRepositoryInternal();
      repository.delete(id);
      return Response.noContent()
        .build();
  }

  private Response update(String id, AttributeReferenceListWrapper attributes, AttributeReferenceListWrapper excludedAttributes, Function<T, UpdateRequest<T>> updateRequestFunction) throws ScimException, ResourceException {

    Repository<T> repository = getRepositoryInternal();

    Set<AttributeReference> attributeReferences = AttributeReferenceListWrapper.getAttributeReferences(attributes);
    Set<AttributeReference> excludedAttributeReferences = AttributeReferenceListWrapper.getAttributeReferences(excludedAttributes);
    validateAttributes(attributeReferences, excludedAttributeReferences);

    T stored = repository.get(id);

    if (stored == null) {
      throw notFoundException(id);
    }

    EntityTag backingETag = requireEtag(stored);
    validatePreconditions(id, backingETag);

    UpdateRequest<T> updateRequest = updateRequestFunction.apply(stored);
    T updated = repository.update(updateRequest);

    // Process Attributes
    updated = processFilterAttributeExtensions(repository, updated, attributeReferences, excludedAttributeReferences);
    updated = attributesForDisplayIgnoreErrors(updated, attributeReferences, excludedAttributeReferences);

    EntityTag etag = etag(updated);
    return Response.ok(updated)
      .location(buildLocationTag(updated))
      .tag(etag)
      .build();
  }

  @SuppressWarnings("unchecked")
  private T processFilterAttributeExtensions(Repository<T> repository, T resource, Set<AttributeReference> attributeReferences, Set<AttributeReference> excludedAttributeReferences) throws ScimException {
    ScimProcessingExtension annotation = repository.getClass()
                                                 .getAnnotation(ScimProcessingExtension.class);
    if (annotation != null) {
      Class<? extends ProcessingExtension>[] value = annotation.value();
      for (Class<? extends ProcessingExtension> class1 : value) {
        ProcessingExtension processingExtension = CDI.current().select(class1).get();
        if (processingExtension instanceof AttributeFilterExtension) {
          AttributeFilterExtension attributeFilterExtension = (AttributeFilterExtension) processingExtension;
          ScimRequestContext scimRequestContext = new ScimRequestContext(attributeReferences, excludedAttributeReferences);

          try {
            resource = (T) attributeFilterExtension.filterAttributes(resource, scimRequestContext);
            log.debug("Resource now - " + resource.toString());
          } catch (ClientFilterException e) {
            throw new ScimException(Status.fromStatusCode(e.getStatus()), e.getMessage(), e);
          }
        }
      }
    }

    return resource;
  }

  private URI buildLocationTag(T resource) {
    String id = resource.getId();
    if (id == null) {
      LOG.warn("Repository must supply an id for a resource");
      id = "unknown";
    }
    return uriInfo.getAbsolutePathBuilder()
                  .path(id)
                  .build();
  }

  private <T extends ScimResource> T attributesForDisplay(T resource, Set<AttributeReference> includedAttributes, Set<AttributeReference> excludedAttributes) throws AttributeException {
    if (!excludedAttributes.isEmpty()) {
      resource = attributeUtil.setExcludedAttributesForDisplay(resource, excludedAttributes);
    } else {
      resource = attributeUtil.setAttributesForDisplay(resource, includedAttributes);
    }
    return resource;
  }

  private T attributesForDisplayIgnoreErrors(T resource, Set<AttributeReference> includedAttributes, Set<AttributeReference> excludedAttributes) {
    try {
      return attributesForDisplay(resource, includedAttributes, excludedAttributes);
    } catch (AttributeException e) {
      if (log.isDebugEnabled()) {
        log.debug("Failed to handle attribute processing in update " + e.getMessage(), e);
      } else {
        log.warn("Failed to handle attribute processing in update " + e.getMessage());
      }
    }
    return resource;
  }

  private T attributesForDisplayThrowOnError(T resource, Set<AttributeReference> includedAttributes, Set<AttributeReference> excludedAttributes) throws ScimException {
    try {
      return attributesForDisplay(resource, includedAttributes, excludedAttributes);
    } catch (AttributeException e) {
      throw new ScimException(Status.INTERNAL_SERVER_ERROR, "Failed to parse the attribute query value " + e.getMessage(), e);
    }
  }

  private ScimException notFoundException(String id) {
    return new ScimException(Status.NOT_FOUND, "Resource " + id + " not found");
  }

  private void validatePreconditions(String id, EntityTag etag) {
    ResponseBuilder response = request.evaluatePreconditions(etag);
    if (response != null) {
      throw new WebApplicationException(response
        .entity(new ErrorResponse(Status.PRECONDITION_FAILED, "Failed to update record, backing record has changed - " + id))
        .build());
    }
  }

  private EntityTag requireEtag(ScimResource resource) throws ScimException {
    try {
      return etagGenerator.generateEtag(resource);
    } catch (EtagGenerationException e) {
      throw new ScimException(Status.INTERNAL_SERVER_ERROR, "Failed to generate the etag", e);
    }
  }

  private EntityTag etag(ScimResource resource) {
    try {
      return etagGenerator.generateEtag(resource);
    } catch (EtagGenerationException e) {
      log.warn("Failed to generate etag for resource", e);
      return null;
    }
  }

  private void validateAttributes(Set<AttributeReference> attributeReferences, Set<AttributeReference> excludedAttributeReferences) throws ScimException {
    if (!attributeReferences.isEmpty() && !excludedAttributeReferences.isEmpty()) {
      throw new ScimException(Status.BAD_REQUEST, "Cannot include both attributes and excluded attributes in a single request");
    }
  }
}
