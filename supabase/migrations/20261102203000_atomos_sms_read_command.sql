alter table private.device_commands drop constraint if exists device_commands_command_type_check;
alter table private.device_commands add constraint device_commands_command_type_check check (command_type in ('inspect','tap','swipe','back','home','notifications','type','sms_fetch'));
