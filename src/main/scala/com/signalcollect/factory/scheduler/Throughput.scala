/*
 *  @author Philip Stutz
 *
 *  Copyright 2012 University of Zurich
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.signalcollect.factory.scheduler

import com.signalcollect.interfaces.SchedulerFactory
import com.signalcollect.interfaces.Scheduler
import com.signalcollect.interfaces.Worker
import com.signalcollect.scheduler.ThroughputScheduler

/**
 *  The low-latency scheduler tries to maximize the signaling/collecting
 *  throughput and prevent excessive memory usage.
 */
object Throughput extends SchedulerFactory {
  def createInstance[Id](worker: Worker[Id, _]): Scheduler[Id] = {
    new ThroughputScheduler(worker)
  }
  override def toString: String = "Throughput Scheduler Factory"
}
