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
#include "../lib/clientimpl.h"
#include <hedwig/exceptions.h>
#include <hedwig/callback.h>
#include <stdexcept>
#include <pthread.h>
#include <boost/thread/mutex.hpp>
#include <boost/thread/condition_variable.hpp>

#include <log4cxx/logger.h>

static log4cxx::LoggerPtr utillogger(log4cxx::Logger::getLogger("hedwig."__FILE__));

class SimpleWaitCondition {
public:
 SimpleWaitCondition() : flag(false), success(false) {};
  ~SimpleWaitCondition() { wait(); }

  void wait() {
    boost::unique_lock<boost::mutex> lock(mut);
    while(!flag)
    {
        cond.wait(lock);
    }
  }

  void notify() {
    {
      boost::lock_guard<boost::mutex> lock(mut);
      flag = true;
    }
    cond.notify_all();
  }

  void setSuccess(bool s) {
    success = s;
  }

  bool wasSuccess() {
    return success;
  }

private:
  bool flag;
  boost::condition_variable cond;
  boost::mutex mut;
  bool success;
};

class TestPublishResponseCallback : public Hedwig::PublishResponseCallback {
public:
  TestPublishResponseCallback(SimpleWaitCondition* cond) : cond(cond) {
  }

  virtual void operationComplete(const Hedwig::PublishResponsePtr & resp) {
    LOG4CXX_DEBUG(utillogger, "operationComplete");
    pubResp = resp;
    cond->setSuccess(true);
    cond->notify();
  }
  
  virtual void operationFailed(const std::exception& exception) {
    LOG4CXX_DEBUG(utillogger, "operationFailed: " << exception.what());
    cond->setSuccess(false);
    cond->notify();
  }    

  Hedwig::PublishResponsePtr getResponse() {
    return pubResp;
  }
private:
  SimpleWaitCondition *cond;
  Hedwig::PublishResponsePtr pubResp;
};

class TestCallback : public Hedwig::OperationCallback {
public:
  TestCallback(SimpleWaitCondition* cond) 
    : cond(cond) {
  }

  virtual void operationComplete() {
    LOG4CXX_DEBUG(utillogger, "operationComplete");
    cond->setSuccess(true);
    cond->notify();

  }
  
  virtual void operationFailed(const std::exception& exception) {
    LOG4CXX_DEBUG(utillogger, "operationFailed: " << exception.what());
    cond->setSuccess(false);
    cond->notify();
  }    
  

private:
  SimpleWaitCondition *cond;
};


class TestServerConfiguration : public Hedwig::Configuration {
public:
  TestServerConfiguration() : address("localhost:4081"), syncTimeout(10000), numThreads(2) {}

  TestServerConfiguration(int syncTimeout, int numThreads = 2) : address("localhost:4081"), syncTimeout(syncTimeout), numThreads(numThreads) {}
  
  virtual int getInt(const std::string& key, int defaultVal) const {
    if (key == Configuration::SYNC_REQUEST_TIMEOUT) {
      return syncTimeout;
    } else if (key == Configuration::NUM_DISPATCH_THREADS) {
      return numThreads;
    }
    return defaultVal;
  }

  virtual const std::string get(const std::string& key, const std::string& defaultVal) const {
    if (key == Configuration::DEFAULT_SERVER) {
      return address;
    } else {
      return defaultVal;
    }
  }

  virtual bool getBool(const std::string& /*key*/, bool defaultVal) const {
    return defaultVal;
  }
  
private:
  const std::string address;
  const int syncTimeout;
  const int numThreads;
};

