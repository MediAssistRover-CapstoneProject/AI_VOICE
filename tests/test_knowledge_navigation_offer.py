from uuid import uuid4
from types import SimpleNamespace
from src.web.robot_api import information_destination, navigation_action


def test_wheelchair_answer_offers_reception_endpoint_only():
    reception=str(uuid4())
    points=[dict(endpointId=reception,roomName='Phòng lễ tân',endpointName='phòng lễ tân')]
    action=information_destination('Xe lăn ở đâu?', 'Xe lăn được bố trí tại Phòng lễ tân.', points)
    assert action == dict(kind='NAVIGATE',endpoint_id=reception,destination_label='Phòng lễ tân')
    assert information_destination('Xe lăn ở đâu?', 'Tôi không trả lời thông tin này được.', points) is None
    assert information_destination('Xe lăn ở đâu?', 'Xe lăn được bố trí tại Phòng lễ tân.', []) is None


def test_room_location_offers_only_one_matching_current_endpoint():
    destination=str(uuid4())
    points=[dict(endpointId=destination,roomName='Phòng x quang',endpointName='phòng x quang'),
            dict(endpointId=str(uuid4()),roomName='Phòng thuốc',endpointName='phòng thuốc')]
    assert information_destination('Phòng x quang ở đâu?', 'Map có điểm Phòng x quang.',points)['endpoint_id']==destination
    assert information_destination('Phòng x quang ở đâu?', 'Map có điểm Phòng x quang.',points+[points[0]]) is None
    assert information_destination('Phòng x quang làm gì?', 'Phòng x quang chụp X-quang.',points) is None
    assert information_destination('Phòng X-quang ở đâu?', 'Đây là Phòng X-quang.',points)['endpoint_id']==destination


def test_location_question_uses_knowledge_before_navigation():
    question=SimpleNamespace(text='Phòng X-quang ở đâu?', destinations=[])
    assert navigation_action(question, SimpleNamespace(intent='ESCORT_REQUEST')) == (False,None)
