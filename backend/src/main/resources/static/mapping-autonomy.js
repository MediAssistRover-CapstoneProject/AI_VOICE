/* Session scoped, short-lived autonomy control. No commands on page load. */
(function(root){
  'use strict';
  class AutonomyRun {
    constructor(send,onChange,onFinish){
      this.send=send;this.onChange=onChange;this.onFinish=onFinish;
      this.active=false;this.generation=0;this.timer=null;this.limit=null;this.run=null;this.context=null;
    }
    start(context,seconds,stopOnReturn,saveAfter){
      if(this.active)throw Error('Dừng chế độ tự chạy hiện tại trước.');
      if(!Number.isInteger(seconds)||seconds<10||seconds>600)throw Error('Thời gian tự chạy từ 10 đến 600 giây.');
      this.context={...context};this.saveAfter=saveAfter;this.stopMessage=null;
      this.run={action:'START',runId:crypto.randomUUID(),maxDurationSec:seconds,stopOnReturn:!!stopOnReturn,saveOnComplete:!!saveAfter};
      this.active=true;this.confirmed=false;this.startedAt=performance.now();this.commandIds=new Set();const generation=++this.generation;
      this.limit=setTimeout(()=>this.stop('Hết thời gian trên trình duyệt; đã ngừng gia hạn.').catch(()=>{}),(seconds+3)*1000);
      this.onChange('Đã gửi yêu cầu tự chạy; đang chờ trạng thái từ Pi.');
      this.renew(generation,true);
    }
    async renew(generation,first=false){
      if(!this.active||generation!==this.generation)return;
      const started=performance.now();
      try{
        if(!this.confirmed&&performance.now()-this.startedAt>3500)throw Error('Pi chưa xác nhận START; kiểm tra receipt và đồng hồ BE/Pi.');
        const receipt=await this.send({...this.run,action:this.confirmed?'RENEW':'START'},this.context);
        if(!this.active||generation!==this.generation)return;
        if(receipt?.commandId){this.commandIds.add(receipt.commandId);if(this.commandIds.size>16)this.commandIds.delete(this.commandIds.values().next().value);}
        if(['PI_REJECTED','NO_PI_ACK','PUBLISH_FAILED'].includes(receipt?.status))throw Error(receipt.errorCode||receipt.status);
        if(receipt?.status==='PI_ACCEPTED')this.confirmed=true;
        this.timer=setTimeout(()=>this.renew(generation),Math.max(0,300-(performance.now()-started)));
      }catch(e){if(this.active&&generation===this.generation)await this.stop('Tự chạy đã ngừng gia hạn: '+e.message).catch(()=>{});}
    }
    observeReceipt(receipt){
      if(!this.active||!this.commandIds?.has(receipt?.commandId))return;
      if(receipt.status==='PI_ACCEPTED')this.confirmed=true;
      if(['PI_REJECTED','NO_PI_ACK','PUBLISH_FAILED'].includes(receipt.status))
        this.stop('Pi chưa thực hiện lệnh: '+(receipt.errorCode||receipt.status)).catch(()=>{});
    }
    cancel(){this.active=false;++this.generation;clearTimeout(this.timer);clearTimeout(this.limit);}
    async stop(message='Đã ngừng gia hạn tự chạy và gửi yêu cầu dừng.'){
      const run=this.run&&{...this.run,action:'STOP'},context=this.context;
      this.cancel();this.stopMessage=message;this.onChange(message);
      if(run&&context)return this.send(run,context);
    }
    observe(report,context){
      if(!this.active)return;
      if(context.job!==this.context.job||context.session!==this.context.session||!context.mapping){
        this.stop('Phiên quét thay đổi; đã ngừng tự chạy.').catch(()=>{});return;
      }
      if(report?.run_id!==this.run.runId)return;
      if(report.state==='RUNNING')this.confirmed=true;
      this.onChange('Pi: '+report.state+' / '+report.reason+' • '+report.elapsed_sec+' giây • '+report.travel_m+' m.');
      if(report.finished){
        const covered=report.reason==='COVERAGE_COMPLETE'&&report.coverage_complete===true&&report.exploration?.state==='COMPLETE';
        const finish=this.saveAfter&&(['TIME_LIMIT','RETURNED_TO_START'].includes(report.reason)||covered&&report.save_on_complete!==true),generation=this.generation;
        this.cancel();
        if(finish)this.onFinish(this.context,report,generation);
      }
    }
  }
  function mount(host){
    const el=document.getElementById('mappingAutonomy');
    el.innerHTML='<h2>Tự chạy tránh vật cản để quét map</h2>'+
      '<p>Profile frontier: Pi tìm vùng chưa biết có đường tiếp cận, giảm tốc theo LiDAR và dừng khi không còn frontier đáng kể trong vùng đi tới được, sau thời gian xác nhận. Màu đen là vật cản; màu xám ngoài tường được giữ nguyên. Profile reactive cũ chỉ tránh vật cản theo thời gian.</p>'+
      '<label>Giới hạn thời gian (giây)<input id="autoSeconds" type="number" min="10" max="600" value="180"></label>'+
      '<label><input id="autoReturn" type="checkbox"> Dừng nếu đi qua gần điểm đầu sau khi đã rời xa và đi đủ quãng đường</label>'+
      '<label><input id="autoSave" type="checkbox"> Lưu map và kết thúc khi hoàn tất, hết thời gian hoặc trở lại gần điểm đầu (hết thời gian không có nghĩa đã quét hết)</label>'+
      '<button id="autoStart" disabled>Bắt đầu tự quét</button> <button id="autoStop" disabled>Dừng tự chạy</button>'+
      '<small id="autoStatus" role="status">Chờ Pi có bản vá và cấu hình kích thước robot.</small>'+
      '<pre id="autoReport">Chưa có trạng thái tự chạy.</pre>';
    const $=id=>document.getElementById(id);
    let report=null,fresh=false,saving=false;
    const run=new AutonomyRun(host.send,message=>{$('autoStatus').textContent=message;host.buttons();},async(context,state)=>{
      if(saving||host.context().job!==context.job||host.context().session!==context.session)return;
      saving=true;
      try{await host.save();$('autoStatus').textContent='Pi đã dừng vì '+state.reason+'. Đã yêu cầu lưu; chờ Job SUCCEEDED rồi xem map đã lưu.';}
      catch(e){$('autoStatus').textContent='Chưa lưu được map: '+e.message;}
      finally{saving=false;host.buttons();}
    });
    $('autoStart').onclick=()=>{
      const c=host.context();
      if(!c.mapping||c.busy||c.jogActive||!fresh||!report?.available)return;
      try{run.start(c,Number($('autoSeconds').value),report?.exploration_mode==='frontier'?false:$('autoReturn').checked,$('autoSave').checked);}
      catch(e){$('autoStatus').textContent=e.message;}
    };
    $('autoStop').onclick=async()=>{try{await run.stop();await host.manualStop();}catch(e){$('autoStatus').textContent='Chưa xác nhận dừng: '+e.message;}};
    return {
      observeReceipt(receipt){run.observeReceipt(receipt);},
      get active(){return run.active;},
      get owned(){return run.active||fresh&&!!report?.control_selected;},
      cancel(){run.cancel();},
      async stop(){return run.stop();},
      render(state){
        const c=host.context(),age=(Date.now()-Date.parse(state.motionObservedAt))/1000;
        report=state.autonomy;
        fresh=Number.isFinite(age)&&age>=-1&&age<15;
        $('autoReport').textContent=JSON.stringify({observedAt:state.motionObservedAt,...report},null,2);
        const max=report?.profile_max_duration_sec;
        if(Number.isFinite(max)){$('autoSeconds').max=max;if(Number($('autoSeconds').value)>max)$('autoSeconds').value=max;}
        run.observe(report,c);
        if(!run.active)$('autoStatus').textContent=run.stopMessage||(report?.available&&fresh?
          'Pi: '+report.state+' / '+report.reason+(report.control_selected?'. Bấm Dừng tự chạy để trả quyền điều khiển tay.':'. Chờ phiên map sẵn sàng rồi bấm Bắt đầu tự quét.'):
          'Pi chưa cấu hình tự chạy hoặc heartbeat đã cũ.');
        if(report?.exploration_mode==='frontier'&&report.state!=='IDLE'&&!saving){
          const exploration=report.exploration;
          $('autoStatus').textContent+=' • Frontier: '+(exploration?.frontier_count??'đang tính')+
            ' • '+(report.coverage_complete?'Đã hoàn tất vùng tiếp cận được.':exploration?.reason||'Chưa xác nhận quét hết.');
        }
        this.buttons();
      },
      buttons(){
        const c=host.context(),owned=run.active||fresh&&!!report?.control_selected;
        $('autoStart').disabled=!c.mapping||c.busy||c.jogActive||owned||saving||!fresh||!report?.available;
        $('autoStop').disabled=!c.session||c.busy;
        for(const id of ['autoSeconds','autoReturn','autoSave'])$(id).disabled=owned||saving;
        if(report?.exploration_mode==='frontier'){$('autoReturn').checked=false;$('autoReturn').disabled=true;}
      }
    };
  }
  const api={AutonomyRun,mount};
  if(typeof module!=='undefined'&&module.exports)module.exports=api;
  else root.MappingAutonomy=api;
})(typeof globalThis!=='undefined'?globalThis:this);
