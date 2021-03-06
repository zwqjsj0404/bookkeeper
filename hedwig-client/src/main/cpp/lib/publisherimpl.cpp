 /**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#ifdef HAVE_CONFIG_H
#include <config.h>
#endif

#include "publisherimpl.h"
#include "channel.h"

#include <log4cxx/logger.h>

static log4cxx::LoggerPtr logger(log4cxx::Logger::getLogger("hedwig."__FILE__));

using namespace Hedwig;

PublishResponseAdaptor::PublishResponseAdaptor(const PublishResponseCallbackPtr& pubCallback)
  : pubCallback(pubCallback) {
}

void PublishResponseAdaptor::operationComplete(const ResponseBody& result) {
  if (result.has_publishresponse()) {
    PublishResponse *resp = new PublishResponse();
    resp->CopyFrom(result.publishresponse());
    PublishResponsePtr respPtr(resp);
    pubCallback->operationComplete(respPtr);
  } else {
    // return empty response
    pubCallback->operationComplete(PublishResponsePtr());
  }
}

void PublishResponseAdaptor::operationFailed(const std::exception& exception) {
  pubCallback->operationFailed(exception);
}

PublishWriteCallback::PublishWriteCallback(const ClientImplPtr& client, const PubSubDataPtr& data) : client(client), data(data) {}

void PublishWriteCallback::operationComplete() {
  LOG4CXX_DEBUG(logger, "Successfully wrote transaction: " << data->getTxnId());
}

void PublishWriteCallback::operationFailed(const std::exception& exception) {
  LOG4CXX_ERROR(logger, "Error writing to publisher " << exception.what());
  
  data->getCallback()->operationFailed(exception);
}

PublisherImpl::PublisherImpl(const ClientImplPtr& client) 
  : client(client) {
}

PublishResponsePtr PublisherImpl::publish(const std::string& topic, const Message& message) {
  SyncCallback<PublishResponsePtr>* cb =
    new SyncCallback<PublishResponsePtr>(client->getConfiguration().getInt(Configuration::SYNC_REQUEST_TIMEOUT, 
											                                                     DEFAULT_SYNC_REQUEST_TIMEOUT));
  PublishResponseCallbackPtr callback(cb);
  asyncPublishWithResponse(topic, message, callback);
  cb->wait();
  
  cb->throwExceptionIfNeeded();  
  return cb->getResult();
}

PublishResponsePtr PublisherImpl::publish(const std::string& topic, const std::string& message) {
  Message msg;
  msg.set_body(message);
  return publish(topic, msg);
}

void PublisherImpl::asyncPublish(const std::string& topic, const Message& message, const OperationCallbackPtr& callback) {
  // use release after callback to release the channel after the callback is called
  ResponseCallbackPtr respCallback(new ResponseCallbackAdaptor(callback));
  doPublish(topic, message, respCallback);
}

void PublisherImpl::asyncPublish(const std::string& topic, const std::string& message, const OperationCallbackPtr& callback) {
  Message msg;
  msg.set_body(message);
  asyncPublish(topic, msg, callback);
}

void PublisherImpl::asyncPublishWithResponse(const std::string& topic, const Message& message,
                                             const PublishResponseCallbackPtr& callback) {
  ResponseCallbackPtr respCallback(new PublishResponseAdaptor(callback));
  doPublish(topic, message, respCallback);
}

void PublisherImpl::doPublish(const std::string& topic, const Message& message, const ResponseCallbackPtr& callback) {
  PubSubDataPtr data = PubSubData::forPublishRequest(client->counter().next(), topic, message, callback);
  
  DuplexChannelPtr channel = client->getChannel(topic);

  doPublish(channel, data);
}

void PublisherImpl::doPublish(const DuplexChannelPtr& channel, const PubSubDataPtr& data) {
  channel->storeTransaction(data);
  
  OperationCallbackPtr writecb(new PublishWriteCallback(client, data));
  channel->writeRequest(data->getRequest(), writecb);
}

void PublisherImpl::messageHandler(const PubSubResponsePtr& m, const PubSubDataPtr& txn) {
  switch (m->statuscode()) {
  case SUCCESS:
    if (m->has_responsebody()) {
      txn->getCallback()->operationComplete(m->responsebody());
    } else {
      txn->getCallback()->operationComplete(ResponseBody());
    }
    break;
  case SERVICE_DOWN:
    LOG4CXX_ERROR(logger, "Server responsed with SERVICE_DOWN for " << txn->getTxnId());
    txn->getCallback()->operationFailed(ServiceDownException());
    break;
  default:
    LOG4CXX_ERROR(logger, "Unexpected response " << m->statuscode() << " for " << txn->getTxnId());
    txn->getCallback()->operationFailed(UnexpectedResponseException());
    break;
  }
}
