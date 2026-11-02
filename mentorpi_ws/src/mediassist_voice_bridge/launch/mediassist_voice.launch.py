from launch import LaunchDescription
from launch.actions import DeclareLaunchArgument
from launch.substitutions import LaunchConfiguration
from launch_ros.actions import Node


def generate_launch_description():
    args = [
        DeclareLaunchArgument('backend_url'), DeclareLaunchArgument('input_device', default_value=''),
        DeclareLaunchArgument('output_device', default_value=''), DeclareLaunchArgument('ca_certificate', default_value=''),
        DeclareLaunchArgument('language', default_value='vi-VN'), DeclareLaunchArgument('capture_directory', default_value='/var/lib/mediassist-voice/captures'),
        DeclareLaunchArgument('journal_path', default_value='/var/lib/mediassist-voice/journal.sqlite3'),
        DeclareLaunchArgument('wake_mode', default_value='auto'), DeclareLaunchArgument('serial_port', default_value='/dev/wonderecho'),
        DeclareLaunchArgument('vosk_model_path', default_value='/opt/mediassist/vosk-model'), DeclareLaunchArgument('vosk_model_sha256', default_value=''),
    ]
    common = {'ca_certificate': LaunchConfiguration('ca_certificate')}
    nodes = [
        Node(package='mediassist_voice_bridge', executable='audio_capture_node', output='screen', parameters=[{
            'input_device': LaunchConfiguration('input_device'), 'capture_directory': LaunchConfiguration('capture_directory'),
            'sample_rate': 16000, 'max_seconds': 20, 'max_bytes': 5242880, 'start_speech_timeout_seconds': 10.0}]),
        Node(package='mediassist_voice_bridge', executable='backend_voice_bridge_node', output='screen', parameters=[{
            **common, 'backend_url': LaunchConfiguration('backend_url'), 'language': LaunchConfiguration('language'),
            'journal_path': LaunchConfiguration('journal_path')}]),
        Node(package='mediassist_voice_bridge', executable='audio_playback_node', output='screen', parameters=[{
            **common, 'output_device': LaunchConfiguration('output_device')}]),
        Node(package='mediassist_voice_bridge', executable='wake_word_node', output='screen', parameters=[{
            'mode': LaunchConfiguration('wake_mode'), 'serial_port': LaunchConfiguration('serial_port'),
            'input_device': LaunchConfiguration('input_device'), 'vosk_model_path': LaunchConfiguration('vosk_model_path'),
            'vosk_model_sha256': LaunchConfiguration('vosk_model_sha256')}]),
        Node(package='mediassist_voice_bridge', executable='conversation_coordinator_node', output='screen', parameters=[{
            'language': LaunchConfiguration('language')}]),
    ]
    # Intentionally excludes vendor agent_process, llm_control_move, function_call,
    # vllm_navigation and cloud tts_node from the hospital launch profile.
    return LaunchDescription(args + nodes)
