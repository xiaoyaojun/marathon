"""Marathon acceptance tests for DC/OS.  This test suite specifically tests the root
   Marathon.
"""

import os
import pytest
import retrying
import shakedown
import time
import uuid
import common

from common import event_fixture
from dcos import marathon
from utils import fixture_dir, get_resource

PACKAGE_NAME = 'marathon'
DCOS_SERVICE_URL = shakedown.dcos_service_url(PACKAGE_NAME)
WAIT_TIME_IN_SECS = 300


def test_default_user():
    """ Ensures the default user of a task is started as root.  This is the default user.
    """

    # launch unique-sleep
    application_json = get_resource("{}/unique-sleep.json".format(fixture_dir()))
    client = marathon.create_client()
    client.add_app(application_json)
    shakedown.deployment_wait()
    app = client.get_app(application_json['id'])
    assert app['user'] is None

    # wait for deployment to finish
    tasks = client.get_tasks("unique-sleep")
    host = tasks[0]['host']

    assert shakedown.run_command_on_agent(host, "ps aux | grep '[s]leep ' | awk '{if ($1 !=\"root\") exit 1;}'")

    client = marathon.create_client()
    client.remove_app("/unique-sleep")


def test_launch_mesos_root_marathon_default_graceperiod():
    """  Test the 'taskKillGracePeriodSeconds' of a launched task from the root marathon.
         The graceperiod is the time after a kill sig to allow for a graceful shutdown.
         The default is 3 seconds.  The fetched test.py contains `signal.signal(signal.SIGTERM, signal.SIG_IGN)`.
    """
    app_def = common.app_mesos()
    app_def['id'] = 'grace'
    fetch = [{
            "uri": "https://downloads.mesosphere.com/testing/test.py"
    }]
    app_def['fetch'] = fetch
    app_def['cmd'] = '/opt/mesosphere/bin/python test.py'

    # with marathon_on_marathon():
    client = marathon.create_client()
    client.add_app(app_def)
    shakedown.deployment_wait()

    # after waiting for deployment it exists
    tasks = shakedown.get_service_task('marathon', 'grace')
    assert tasks is not None

    # still present after a scale down.
    client.scale_app('/grace', 0)
    tasks = shakedown.get_service_task('marathon', 'grace')
    assert tasks is not None

    # 3 sec is the default
    # task should be gone after 3 secs
    default_graceperiod = 3
    time.sleep(default_graceperiod + 1)
    tasks = shakedown.get_service_task('marathon', 'grace')
    assert tasks is None


def test_launch_mesos_root_marathon_graceperiod():
    """  Test the 'taskKillGracePeriodSeconds' of a launched task from the root marathon.
         The default is 3 seconds.  This tests setting that period to other than the default value.
    """
    app_def = common.app_mesos()
    app_def['id'] = 'grace'
    default_graceperiod = 3
    graceperiod = 20
    app_def['taskKillGracePeriodSeconds'] = graceperiod
    fetch = [{
            "uri": "https://downloads.mesosphere.com/testing/test.py"
    }]
    app_def['fetch'] = fetch
    app_def['cmd'] = '/opt/mesosphere/bin/python test.py'

    client = marathon.create_client()
    client.add_app(app_def)
    shakedown.deployment_wait()

    tasks = shakedown.get_service_task('marathon', 'grace')
    assert tasks is not None

    client.scale_app('/grace', 0)
    tasks = shakedown.get_service_task('marathon', 'grace')
    assert tasks is not None

    # task should still be here after the default_graceperiod
    time.sleep(default_graceperiod + 1)
    tasks = shakedown.get_service_task('marathon', 'grace')
    assert tasks is not None

    # but not after the set graceperiod
    time.sleep(graceperiod)
    tasks = shakedown.get_service_task('marathon', 'grace')
    assert tasks is None


def test_declined_offer_due_to_resource_role():
    """ Tests that an offer was declined because the role doesn't exist
    """
    app_id = '/{}'.format(uuid.uuid4().hex)
    app_def = common.pending_deployment_due_to_resource_roles(app_id)

    _test_declined_offer(app_id, app_def, 'UnfulfilledRole')


def test_declined_offer_due_to_cpu_requirements():
    """ Tests that an offer was declined because the number of cpus can't be found in an offer
    """
    app_id = '/{}'.format(uuid.uuid4().hex)
    app_def = common.pending_deployment_due_to_cpu_requirement(app_id)

    _test_declined_offer(app_id, app_def, 'InsufficientCpus')


@pytest.mark.usefixtures("event_fixture")
def test_event_channel():
    """ Tests the event channel.  The way events are verified is by streaming the events
        to a test.txt file.   The fixture ensures the file is removed before and after the test.
        events checked are connecting, deploying a good task and killing a task.
    """
    app_def = common.app_mesos()
    app_id = app_def['id']

    client = marathon.create_client()
    client.add_app(app_def)
    shakedown.deployment_wait()

    @retrying.retry(wait_fixed=1000, stop_max_delay=10000)
    def check_deployment_message():
        status, stdout = shakedown.run_command_on_master('cat test.txt')
        assert 'event_stream_attached' in stdout
        assert 'deployment_info' in stdout
        assert 'deployment_step_success' in stdout

    client.remove_app(app_id, True)
    shakedown.deployment_wait()

    @retrying.retry(wait_fixed=1000, stop_max_delay=10000)
    def check_kill_message():
        status, stdout = shakedown.run_command_on_master('cat test.txt')
        assert 'Killed' in stdout


def _test_declined_offer(app_id, app_def, reason):
    """ Used to confirm that offers were declined.   The `processedOffersSummary` and these tests
        in general require 1.4+ marathon with the queue end point.
        The retry is the best possible way to "time" the success of the test.
    """

    client = marathon.create_client()
    client.add_app(app_def)

    @retrying.retry(wait_fixed=1000, stop_max_delay=10000)
    def verify_declined_offer():
        deployments = client.get_deployments(app_id)
        assert len(deployments) == 1

        offer_summary = client.get_queued_app(app_id)['processedOffersSummary']
        role_summary = declined_offer_by_reason(offer_summary['rejectSummaryLastOffers'], reason)
        last_attempt = declined_offer_by_reason(offer_summary['rejectSummaryLaunchAttempt'], reason)

        assert role_summary['declined'] > 0
        assert role_summary['processed'] > 0
        assert last_attempt['declined'] > 0
        assert last_attempt['processed'] > 0


def test_private_repository_docker_app():
    # Create and copy docker credentials to all private agents
    assert 'DOCKER_HUB_USERNAME' in os.environ, "Couldn't find docker hub username. $DOCKER_HUB_USERNAME is not set"
    assert 'DOCKER_HUB_PASSWORD' in os.environ, "Couldn't find docker hub password. $DOCKER_HUB_PASSWORD is not set"

    username = os.environ['DOCKER_HUB_USERNAME']
    password = os.environ['DOCKER_HUB_PASSWORD']
    agents = shakedown.get_private_agents()

    common.create_docker_credentials_file(username, password)
    common.copy_docker_credentials_file(agents)

    client = marathon.create_client()
    app_def = common.private_docker_container_app()
    client.add_app(app_def)
    shakedown.deployment_wait()

    common.assert_app_tasks_running(client, app_def)


@pytest.mark.skip(reason="Not yet implemented in mesos")
def test_private_repository_mesos_app():
    """ Test private docker registry with mesos containerizer using "credentials" container field.
        Note: Despite of what DC/OS docmentation states this feature is not yet implemented:
        https://issues.apache.org/jira/browse/MESOS-7088
    """

    client = marathon.create_client()
    assert 'DOCKER_HUB_USERNAME' in os.environ, "Couldn't find docker hub username. $DOCKER_HUB_USERNAME is not set"
    assert 'DOCKER_HUB_PASSWORD' in os.environ, "Couldn't find docker hub password. $DOCKER_HUB_PASSWORD is not set"

    principal = os.environ['DOCKER_HUB_USERNAME']
    secret = os.environ['DOCKER_HUB_PASSWORD']

    app_def = common.private_mesos_container_app(principal, secret)
    client.add_app(app_def)
    shakedown.deployment_wait()

    common.assert_app_tasks_running(client, app_def)


def test_external_volume():
    volume_name = "marathon-si-test-vol-{}".format(uuid.uuid4().hex)
    app_def = common.external_volume_mesos_app(volume_name)
    app_id = app_def['id']

    # Tested with root marathon since MoM doesn't have
    # --enable_features external_volumes option activated.
    # First deployment should create the volume since it has a unique name
    try:
        client = marathon.create_client()
        client.add_app(app_def)
        shakedown.deployment_wait()

        # Create the app: the volume should be successfully created
        common.assert_app_tasks_running(client, app_def)
        common.assert_app_tasks_healthy(client, app_def)

        # Scale down to 0
        client.stop_app(app_id)
        shakedown.deployment_wait()

        # Scale up again: the volume should be successfully reused
        client.scale_app(app_id, 1)
        shakedown.deployment_wait()

        common.assert_app_tasks_running(client, app_def)
        common.assert_app_tasks_healthy(client, app_def)

        # Remove the app to be able to remove the volume
        client.remove_app(app_id)
        shakedown.deployment_wait()
    except Exception as e:
        print('Fail to test external volumes: {}'.format(e))
        raise e
    finally:
        # Clean up after the test: external volumes are not destroyed by marathon or dcos
        # and have to be cleaned manually.
        agent = shakedown.get_private_agents()[0]
        result, output = shakedown.run_command_on_agent(agent, 'sudo /opt/mesosphere/bin/dvdcli remove --volumedriver=rexray --volumename={}'.format(volume_name))
        # Note: Removing the volume might fail sometimes because EC2 takes some time (~10min) to recognize that
        # the volume is not in use anymore hence preventing it's removal. This is a known pitfall: we log the error
        # and the volume should be cleaned up manually later.
        if not result:
            print('WARNING: Failed to remove external volume with name={}: {}'.format(volume_name, output))


def setup_function(function):
    common.stop_all_deployments()
    common.delete_all_apps_wait()


def setup_module(module):
    common.cluster_info()


def declined_offer_by_reason(offers, reason):
    for offer in offers:
        if offer['reason'] == reason:
            del offer['reason']
            return offer

    return None


def teardown_module(module):
    common.stop_all_deployments()
    common.delete_all_apps_wait()
