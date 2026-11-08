"""正式验收必须执行或完整复核已有 campaign，不用 skip 签发结果。"""
import json
import os
import pytest
from analysis.summarize_layer1 import summarize_campaign
from bench.layer1 import execute
from bench.models import ROOT

@pytest.mark.slow
def test_complete_matrix(server_manager):
    server_manager.enter_benchmark()
    campaign=execute('run',ROOT/'bench/areas.json',ROOT/'bench/protocol.json',os.environ.get('MCD_CAMPAIGN_ID'))
    summaries,differences=summarize_campaign(campaign,publish=True)
    assert len(summaries)==9 and all(row['cv']<.1 for row in summaries)
    assert len(differences)==27
    assert json.loads((campaign/'result.json').read_text())['status']=='passed'
