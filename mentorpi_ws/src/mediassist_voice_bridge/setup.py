from setuptools import find_packages, setup
from glob import glob
import os

package_name = 'mediassist_voice_bridge'
setup(
    name=package_name, version='0.1.0', packages=find_packages(),
    data_files=[
        ('share/ament_index/resource_index/packages', ['resource/' + package_name]),
        ('share/' + package_name, ['package.xml']),
        (os.path.join('share', package_name, 'launch'), glob('launch/*.launch.py')),
    ],
    install_requires=['setuptools', 'requests', 'sounddevice', 'webrtcvad-wheels', 'pyserial', 'vosk'], zip_safe=True,
    maintainer='MediAssist', maintainer_email='dev@mediassist.local',
    description='MentorPi audio capture, backend upload and verified playback without motor control.', license='Apache-2.0',
    entry_points={'console_scripts': [
        'audio_capture_node = mediassist_voice_bridge.audio_capture_node:main',
        'backend_voice_bridge_node = mediassist_voice_bridge.backend_voice_bridge_node:main',
        'audio_playback_node = mediassist_voice_bridge.audio_playback_node:main',
        'wake_word_node = mediassist_voice_bridge.wake_word_node:main',
        'conversation_coordinator_node = mediassist_voice_bridge.conversation_coordinator_node:main',
    ]},
)
