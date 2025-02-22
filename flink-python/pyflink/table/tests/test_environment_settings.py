################################################################################
#  Licensed to the Apache Software Foundation (ASF) under one
#  or more contributor license agreements.  See the NOTICE file
#  distributed with this work for additional information
#  regarding copyright ownership.  The ASF licenses this file
#  to you under the Apache License, Version 2.0 (the
#  "License"); you may not use this file except in compliance
#  with the License.  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
# limitations under the License.
################################################################################
from pyflink.java_gateway import get_gateway

from pyflink.common import Configuration
from pyflink.table import EnvironmentSettings
from pyflink.testing.test_case_utils import PyFlinkTestCase
from pyflink.util.java_utils import get_field_value


class EnvironmentSettingsTests(PyFlinkTestCase):

    def test_planner_selection(self):

        builder = EnvironmentSettings.new_instance()

        # test the default behaviour to make sure it is consistent with the python doc
        environment_settings = builder.build()

        self.check_blink_planner(environment_settings)

        # test use_blink_planner
        environment_settings = EnvironmentSettings.new_instance().use_blink_planner().build()

        self.check_blink_planner(environment_settings)

        # test use_any_planner
        environment_settings = builder.use_any_planner().build()

        self.check_any_planner(environment_settings)

    def test_mode_selection(self):

        builder = EnvironmentSettings.new_instance()

        # test the default behaviour to make sure it is consistent with the python doc
        environment_settings = builder.build()
        self.assertTrue(environment_settings.is_streaming_mode())

        # test in_streaming_mode
        environment_settings = builder.in_streaming_mode().build()
        self.assertTrue(environment_settings.is_streaming_mode())

        environment_settings = EnvironmentSettings.in_streaming_mode()
        self.assertTrue(environment_settings.is_streaming_mode())

        # test in_batch_mode
        environment_settings = builder.in_batch_mode().build()
        self.assertFalse(environment_settings.is_streaming_mode())

        environment_settings = EnvironmentSettings.in_batch_mode()
        self.assertFalse(environment_settings.is_streaming_mode())

    def test_with_built_in_catalog_name(self):

        gateway = get_gateway()

        DEFAULT_BUILTIN_CATALOG = gateway.jvm.EnvironmentSettings.DEFAULT_BUILTIN_CATALOG

        builder = EnvironmentSettings.new_instance()

        # test the default behaviour to make sure it is consistent with the python doc
        environment_settings = builder.build()

        self.assertEqual(environment_settings.get_built_in_catalog_name(), DEFAULT_BUILTIN_CATALOG)

        environment_settings = builder.with_built_in_catalog_name("my_catalog").build()

        self.assertEqual(environment_settings.get_built_in_catalog_name(), "my_catalog")

    def test_with_built_in_database_name(self):

        gateway = get_gateway()

        DEFAULT_BUILTIN_DATABASE = gateway.jvm.EnvironmentSettings.DEFAULT_BUILTIN_DATABASE

        builder = EnvironmentSettings.new_instance()

        # test the default behaviour to make sure it is consistent with the python doc
        environment_settings = builder.build()

        self.assertEqual(environment_settings.get_built_in_database_name(),
                         DEFAULT_BUILTIN_DATABASE)

        environment_settings = builder.with_built_in_database_name("my_database").build()

        self.assertEqual(environment_settings.get_built_in_database_name(), "my_database")

    def test_to_configuration(self):

        expected_settings = EnvironmentSettings.new_instance().in_batch_mode().build()
        config = expected_settings.to_configuration()

        self.assertEqual("BATCH", config.get_string("execution.runtime-mode", "stream"))

    def test_from_configuration(self):

        config = Configuration()
        config.set_string("execution.runtime-mode", "batch")

        actual_setting = EnvironmentSettings.from_configuration(config)
        self.assertFalse(actual_setting.is_streaming_mode(), "Use batch mode.")

    def check_blink_planner(self, settings: EnvironmentSettings):
        gateway = get_gateway()
        CLASS_NAME = gateway.jvm.EnvironmentSettings.CLASS_NAME

        builder = EnvironmentSettings.new_instance()
        BLINK_PLANNER_FACTORY = get_field_value(builder._j_builder, "BLINK_PLANNER_FACTORY")

        self.assertEqual(
            settings._j_environment_settings.toPlannerProperties()[CLASS_NAME],
            BLINK_PLANNER_FACTORY)

    def check_any_planner(self, settings: EnvironmentSettings):
        gateway = get_gateway()
        CLASS_NAME = gateway.jvm.EnvironmentSettings.CLASS_NAME

        self.assertTrue(
            CLASS_NAME not in settings._j_environment_settings.toPlannerProperties())
