import{G as $,O as B,W as u,k as F,aj as w,L as c,P as t,$ as b,a0 as n,_ as a,u as r,A as W,M as y,c as g,F as q,Z,aF as J}from"./vendor-BtDxEGf_.js";import{E as A}from"./element-plus-BeMEpVc5.js";import{G as z}from"./GlassCard--osX3fti.js";import{S as f}from"./SoftButton-ejyacAyX.js";import{S as K,R as Q}from"./SoftProgress-DRAiLlxm.js";import{B as Y}from"./BreathingLoader-Biw_7irS.js";import{c as h,_ as L}from"./index-CyqzNTNY.js";import{i as ee,g as te}from"./bank-AQBptnXL.js";import{u as le}from"./bank-B_U9Zn9f.js";import{a as se}from"./format-CUCz8HEC.js";import{C as ae}from"./check-BpofQn0Z.js";import{A as oe}from"./arrow-right-C3KsbffE.js";import{T as ne}from"./triangle-alert-B7--uaKl.js";/**
 * @license lucide-vue-next v1.0.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const ie=h("clipboard-copy",[["rect",{width:"8",height:"4",x:"8",y:"2",rx:"1",ry:"1",key:"tgr4d6"}],["path",{d:"M8 4H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2",key:"4jdomd"}],["path",{d:"M16 4h2a2 2 0 0 1 2 2v4",key:"3hqy98"}],["path",{d:"M21 14H11",key:"1bme5i"}],["path",{d:"m15 10-4 4 4 4",key:"5dvupr"}]]);/**
 * @license lucide-vue-next v1.0.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const E=h("cloud-upload",[["path",{d:"M12 13v8",key:"1l5pq0"}],["path",{d:"M4 14.899A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 2.5 8.242",key:"1pljnt"}],["path",{d:"m8 17 4-4 4 4",key:"1quai1"}]]);/**
 * @license lucide-vue-next v1.0.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const ue=h("file-text",[["path",{d:"M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z",key:"1oefj6"}],["path",{d:"M14 2v5a1 1 0 0 0 1 1h5",key:"wfsgrz"}],["path",{d:"M10 9H8",key:"b1mrlr"}],["path",{d:"M16 13H8",key:"t4e002"}],["path",{d:"M16 17H8",key:"z1uh3a"}]]);/**
 * @license lucide-vue-next v1.0.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const re=h("info",[["circle",{cx:"12",cy:"12",r:"10",key:"1mglay"}],["path",{d:"M12 16v-4",key:"1dtifu"}],["path",{d:"M12 8h.01",key:"e9boi3"}]]),de={class:"guide"},pe={class:"guide-block"},ce={class:"block-head"},me=`【第 1 章】网页基础
【第 1 题】题型：单选题
题目：网页是由 HTML 语言来实现的，HTML 语言是
选项：
  A. 大型数据库
  B. 网页源文件中出现的唯一一种语言
  C. 网络通信协议
  D. 超文本标记语言
正确答案：D`,P=`请按下面的标准格式整理题目文本。

【重要】只调整结构和补充标签：不得改动任何原文文字，不得编造或补全答案，不得增删题目。
【重要】章节标题必须原样保留，一个字都不能改；原文没有章节标题，就不要自己编一个出来。

标准格式：
【第 1 章】计算机网络概述
【第 1 题】题型：单选题
题目：题干内容
选项：
  A. 选项内容
  B. 选项内容
  C. 选项内容
  D. 选项内容
正确答案：A
【第 2 章】物理层
【第 1 题】题型：判断题
题目：题干内容
选项：
  A. 正确
  B. 错误
正确答案：B

格式要求：
1. 章节标题单独占一行，写成「【第 N 章】标题原文」，N 从 1 开始递增。
   原文里的「第一章 绪论」「第1章 绪论」「一、绪论」「专题一」这类写法，都统一成这个格式；
   但标题文字必须原样抄写，不许改写、精简、翻译或补全
2. 章节标题必须写在它所属的那些题之前；原文没有章节标题时，整段都不要输出章节行
3. 每道题以【第 N 题】开头，N 从 1 开始递增（每章内重新从 1 开始即可）
4. 题型只能是这五种之一：单选题、多选题、判断题、填空题、简答题
5. 选项行固定写成「字母. 内容」；判断题也要写成 A. 正确 / B. 错误
6. 答案写法：
   - 单选题：单个字母，如 A
   - 多选题：字母连写、不分隔、升序，如 ACD
   - 判断题：A 表示正确，B 表示错误
   - 填空题：多个空用中文分号「；」分隔
   - 简答题：答案原文
7. 原文没给答案的，「正确答案：」后面留空，不要自己编
8. 只输出整理后的纯文本，不要任何说明文字，不要 markdown 代码块
9. 如果支持文件输出，请把整理结果保存成一个 txt 文件（文件名：整理后的题目.txt）给我下载；
   不支持文件输出就直接输出纯文本，我自行保存成 txt

待整理文本：
（把题目粘贴在这里）`,ve=$({__name:"FormatGuideDialog",props:{modelValue:{type:Boolean}},emits:["update:modelValue"],setup(T,{emit:x}){const M=T,S=x,m=F({get:()=>M.modelValue,set:i=>S("update:modelValue",i)});async function d(){try{await navigator.clipboard.writeText(P),A.success("提示词已复制，粘贴到 AI 对话框即可");return}catch{}const i=document.createElement("textarea");i.value=P,i.style.position="fixed",i.style.opacity="0",document.body.appendChild(i),i.select();const l=document.execCommand("copy");document.body.removeChild(i),l?A.success("提示词已复制，粘贴到 AI 对话框即可"):A.warning("复制失败，请手动选中提示词复制")}return(i,l)=>{const _=w("el-dialog");return c(),B(_,{modelValue:m.value,"onUpdate:modelValue":l[1]||(l[1]=k=>m.value=k),title:"题目格式要求",width:"600px","append-to-body":""},{footer:u(()=>[n(f,{variant:"ghost",onClick:l[0]||(l[0]=k=>m.value=!1)},{default:u(()=>[...l[8]||(l[8]=[a("知道了",-1)])]),_:1})]),default:u(()=>[t("div",de,[l[6]||(l[6]=t("p",{class:"guide-intro"}," 按下面的标准格式整理，识别率最高。文件是从网页或 Word 里复制来的、格式比较乱时， 建议先交给 AI 整理一次再上传。 ",-1)),t("div",{class:"guide-block"},[l[2]||(l[2]=t("span",{class:"block-title"},"标准格式示例",-1)),t("pre",{class:"code-block"},b(me))]),t("div",pe,[t("div",ce,[l[4]||(l[4]=t("span",{class:"block-title"},"用 AI 快速整理",-1)),n(f,{variant:"outline",icon:r(ie),onClick:d},{default:u(()=>[...l[3]||(l[3]=[a(" 复制提示词 ",-1)])]),_:1},8,["icon"])]),l[5]||(l[5]=t("p",{class:"block-desc"}," 复制后粘贴到任意 AI 对话窗口，把题目接在最后面；把 AI 的回复保存成 txt 再上传即可。 ",-1))]),l[7]||(l[7]=t("div",{class:"guide-block"},[t("span",{class:"block-title"},"注意"),t("ul",{class:"tips"},[t("li",null,[a("章节标题写成 "),t("b",null,"【第 N 章】章节名"),a("（或教材式的 "),t("b",null,"习 题 1"),a("，单独占一行），题目会按章节归类；不写就全部归到「默认章节」")]),t("li",null,"题型只能是这五种：单选题、多选题、判断题、填空题、简答题"),t("li",null,[a("判断题答案写 "),t("b",null,"A"),a("（正确）或 "),t("b",null,"B"),a("（错误）")]),t("li",null,[a("多选题答案字母连写、不分隔、升序，例如 "),t("b",null,"ACD")]),t("li",null,"原文没给答案就留空，不要自己编"),t("li",null,[a("题号支持 "),t("b",null,"【第 N 题】"),a("、"),t("b",null,"1."),a("、"),t("b",null,"（1）"),a(" 三种写法；教材里的「1．选择题 / 2．简答题」小节行也会被识别")])])],-1))])]),_:1},8,["modelValue"])}}}),fe=L(ve,[["__scopeId","data-v-cbc83250"]]),ke={class:"bank-import-view"},ye={class:"format-tip"},ge={class:"form-rows"},be={class:"form-row"},_e={class:"form-row"},Ce={style:{display:"flex","align-items":"center",gap:"10px"}},Ae={class:"form-actions"},xe={class:"progress-head"},Ie={class:"progress-file"},we={class:"file-name"},he={class:"file-size"},Me={class:"stage-line"},Se={key:1,class:"result-block success"},Ve={class:"result-figure success"},Be={class:"text-muted"},Fe={key:0,class:"ai-warn"},Te={class:"result-actions"},Ne={key:2,class:"result-block failed"},De={class:"result-figure failed"},ze={class:"error-msg"},Ee={key:0,class:"text-muted"},Pe={key:1,class:"text-muted"},$e={class:"result-actions"},Le=20*1024*1024,Ge=$({__name:"BankImportView",setup(T){const x=J(),M=le(),S=["docx","pdf","txt"],m=g(),d=g(null),i=g(""),l=g(!1),_=g(!1),k=g(!1),o=g(null);let C=null;W(V);function N(s){var p;const e=((p=s.name.split(".").pop())==null?void 0:p.toLowerCase())??"";return S.includes(e)?s.size>Le?(A.error("文件大小不能超过 20MB"),!1):!0:(A.error("仅支持 DOCX / PDF / TXT 文件"),!1)}function G(s,e){var p,I;if(!N(s.raw)){(p=m.value)==null||p.clearFiles(),d.value=null;return}e.length>1&&((I=m.value)==null||I.clearFiles()),d.value=s.raw,i.value||(i.value=s.name.replace(/\.[^.]+$/,""))}function R(s){var p;(p=m.value)==null||p.clearFiles();const e=s[0];N(e)&&(d.value=e,i.value||(i.value=e.name.replace(/\.[^.]+$/,"")))}async function U(){if(!(!d.value||l.value)){l.value=!0;try{const s=await ee({file:d.value,bankName:i.value||void 0,aiParse:_.value});o.value={taskId:s.taskId,status:s.status,fileName:d.value.name,fileSize:d.value.size,progress:0,bankId:null,totalCount:0,errorMessage:null},H()}catch{}finally{l.value=!1}}}function H(){V();const s=async()=>{if(o.value){try{const e=await te(o.value.taskId);if(o.value=e,e.status==="SUCCESS"||e.status==="FAILED"){M.fetchBanks({},!0);return}}catch{}C=setTimeout(s,1500)}};C=setTimeout(s,1500)}function V(){C&&(clearTimeout(C),C=null)}const O=F(()=>{var s,e;return((e=(s=o.value)==null?void 0:s.errorMessage)==null?void 0:e.startsWith("AI 解析中断："))??!1}),X=F(()=>{var s;switch((s=o.value)==null?void 0:s.status){case"PENDING":return"排队等待解析…";case"PARSING":return"解析文档 → 识别题目…";case"AI_FORMATTING":return"AI 正在识别题目结构（文件排版较乱，正逐块抽取）…";case"AI_PROCESSING":return"AI 解析入库中…";default:return"处理中…"}});function D(){var s;V(),o.value=null,d.value=null,i.value="",(s=m.value)==null||s.clearFiles()}return(s,e)=>{const p=w("el-upload"),I=w("el-input"),j=w("el-switch");return c(),y("div",ke,[o.value?(c(),B(z,{key:1,class:"progress-card"},{default:u(()=>[t("div",xe,[n(r(ue),{size:20,"stroke-width":1.6}),t("div",Ie,[t("span",we,b(o.value.fileName),1),t("span",he,b(r(se)(o.value.fileSize)),1)])]),o.value.status!=="SUCCESS"&&o.value.status!=="FAILED"?(c(),y(q,{key:0},[n(K,{value:o.value.progress,"show-text":"",class:"progress-bar"},null,8,["value"]),t("div",Me,[n(Y,{small:""}),t("span",null,b(X.value),1)])],64)):o.value.status==="SUCCESS"?(c(),y("div",Se,[t("div",Ve,[n(r(ae),{size:30,"stroke-width":2})]),e[17]||(e[17]=t("h3",null,"导入完成",-1)),t("p",Be,"共识别 "+b(o.value.totalCount)+" 道题目，已整理入库",1),o.value.aiFailedCount?(c(),y("p",Fe," 其中 "+b(o.value.aiFailedCount)+" 道题未生成解析，不影响做题 ",1)):Z("",!0),t("div",Te,[n(f,{variant:"outline",onClick:D},{default:u(()=>[...e[15]||(e[15]=[a("继续导入",-1)])]),_:1}),n(f,{icon:r(oe),onClick:e[4]||(e[4]=v=>r(x).push(`/banks/${o.value.bankId}`))},{default:u(()=>[...e[16]||(e[16]=[a("查看题库",-1)])]),_:1},8,["icon"])])])):(c(),y("div",Ne,[t("div",De,[n(r(ne),{size:30,"stroke-width":1.6})]),e[20]||(e[20]=t("h3",null,"导入失败",-1)),t("p",ze,b(o.value.errorMessage||"文件解析失败，请检查文件内容"),1),O.value?(c(),y("p",Ee,"题目已入库，可正常使用")):(c(),y("p",Pe,"数据已回滚，不会产生脏数据")),t("div",$e,[n(f,{variant:"outline",icon:r(Q),onClick:D},{default:u(()=>[...e[18]||(e[18]=[a("重新上传",-1)])]),_:1},8,["icon"]),n(f,{variant:"ghost",onClick:e[5]||(e[5]=v=>k.value=!0)},{default:u(()=>[...e[19]||(e[19]=[a("查看格式要求",-1)])]),_:1})])]))]),_:1})):(c(),B(z,{key:0,title:"导入题库",class:"import-card"},{default:u(()=>[n(p,{ref_key:"uploadRef",ref:m,drag:"","auto-upload":!1,limit:1,accept:".docx,.pdf,.txt","on-change":G,"on-exceed":R,class:"upload-area"},{default:u(()=>[n(r(E),{size:42,"stroke-width":1.4,class:"upload-icon"}),e[7]||(e[7]=t("div",{class:"el-upload__text"},[a("拖拽文件到此处，或 "),t("em",null,"点击选择")],-1)),e[8]||(e[8]=t("div",{class:"upload-hint"},"支持 DOCX / PDF / TXT，单个文件 ≤ 20MB",-1))]),_:1},512),t("div",ye,[n(r(re),{size:14,"stroke-width":1.8}),e[9]||(e[9]=t("span",null,"格式比较乱、怕识别不出来？",-1)),t("button",{type:"button",class:"link-btn",onClick:e[0]||(e[0]=v=>k.value=!0)}," 查看格式要求 / 复制 AI 整理提示词 ")]),t("div",ge,[t("div",be,[e[10]||(e[10]=t("label",{class:"form-label"},"题库名称（可选，默认取文件名）",-1)),n(I,{modelValue:i.value,"onUpdate:modelValue":e[1]||(e[1]=v=>i.value=v),placeholder:"例如：数据库期末复习题库"},null,8,["modelValue"])]),t("div",_e,[e[12]||(e[12]=t("label",{class:"form-label"},"AI 解析",-1)),t("div",Ce,[n(j,{modelValue:_.value,"onUpdate:modelValue":e[2]||(e[2]=v=>_.value=v)},null,8,["modelValue"]),e[11]||(e[11]=t("span",{class:"text-muted",style:{"font-size":"13px"}}," 导入后用 AI 逐题生成解析；没有答案的题会自动补答案（较慢，100 题约 3~8 分钟） ",-1))])])]),t("div",Ae,[n(f,{variant:"ghost",onClick:e[3]||(e[3]=v=>r(x).push("/banks"))},{default:u(()=>[...e[13]||(e[13]=[a("返回",-1)])]),_:1}),n(f,{icon:r(E),loading:l.value,disabled:!d.value,onClick:U},{default:u(()=>[...e[14]||(e[14]=[a(" 开始导入 ",-1)])]),_:1},8,["icon","loading","disabled"])])]),_:1})),n(fe,{modelValue:k.value,"onUpdate:modelValue":e[6]||(e[6]=v=>k.value=v)},null,8,["modelValue"])])}}}),et=L(Ge,[["__scopeId","data-v-5c48677b"]]);export{et as default};
