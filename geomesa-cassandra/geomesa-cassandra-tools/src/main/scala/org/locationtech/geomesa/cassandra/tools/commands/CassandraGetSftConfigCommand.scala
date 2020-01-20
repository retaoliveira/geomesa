/***********************************************************************
 * Copyright (c) 2013-2020 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.cassandra.tools.commands

import com.beust.jcommander.Parameters
import org.locationtech.geomesa.cassandra.data.CassandraDataStore
import org.locationtech.geomesa.cassandra.tools.CassandraDataStoreCommand
import org.locationtech.geomesa.cassandra.tools.CassandraDataStoreCommand.CassandraDataStoreParams
import org.locationtech.geomesa.cassandra.tools.commands.CassandraGetSftConfigCommand.CassandraGetSftConfigParameters
import org.locationtech.geomesa.tools.status.{GetSftConfigCommand, GetSftConfigParams}


class CassandraGetSftConfigCommand extends GetSftConfigCommand[CassandraDataStore] with CassandraDataStoreCommand {
  override val params = new CassandraGetSftConfigParameters
}

object CassandraGetSftConfigCommand {
  @Parameters(commandDescription = "Get the SimpleFeatureType definition of a schema")
  class CassandraGetSftConfigParameters extends GetSftConfigParams with CassandraDataStoreParams
}
